package jp.reikai.lookup

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import jp.reikai.yomitan.R
import jp.reikai.yomitan.YomitanEngine
import jp.reikai.yomitan.anki.AnkiAccess
import jp.reikai.yomitan.popup.PopupLookup
import jp.reikai.yomitan.popup.YomitanPopup
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import logcat.LogPriority
import logcat.logcat
import kotlin.math.roundToInt

/**
 * The lookup sheet: Yomitan's results ([YomitanPopup]) in a bottom sheet over [activity]'s content,
 * the one popup of every surface. Drag its handle to resize it or down to close it; a tap outside,
 * the back gesture or Yomitan's own close button close it too. At most about 640 dp wide, so it reads
 * well on a tablet, and in the theme of the page it looks up from.
 *
 * Between lookups the sheet stays laid out in the window, transparent and passing every touch through
 * (a WebView outside a window, or hidden, is throttled, and resizing one costs a frame), so the next
 * lookup only swaps the popup's content. While AnkiDroid is
 * installed but Reikai JP may not use it, Yomitan hides its add buttons without a word; the sheet
 * then offers the one-tap permission. Main thread only; ends with the activity.
 */
internal class LookupSheet(
    private val activity: ComponentActivity,
    private val lookup: JpLookup,
    /** The sheet closed (the lookup activity finishes then). */
    private val onClosed: () -> Unit = {},
) : DefaultLifecycleObserver {

    private val density = activity.resources.displayMetrics.density
    private val root = Overlay()
    private val scrim = View(activity)
    private val sheet = Sheet()
    private val handle = FrameLayout(activity)
    private val notice = LinearLayout(activity)
    private val noticeText = TextView(activity)
    private val noticeButton = Button(activity, null, android.R.attr.borderlessButtonStyle)
    private val body = FrameLayout(activity)
    private val progress = ProgressBar(activity)
    private val message = TextView(activity)

    private var popup: YomitanPopup? = null
    private var current: PopupLookup? = null
    private var token = -1

    /** The last lookup the page finished drawing. */
    private var shownToken = -1

    /** A lookup made ahead of the sheet opening ([preload]), and its token. */
    private var preloaded: PopupLookup? = null
    private var preloadedToken = -1
    private var askedAt = 0L
    private var dark = false
    private var loading: Job? = null
    private var permission: ActivityResultLauncher<String>? = null
    private var refusedEarly: AnkiAccess.Refusal? = null
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = close()
    }

    var showing = false
        private set

    /** The share of the window's height the sheet takes; the reader's last choice is kept. */
    private val savedFraction = lookup.sheetHeight()
    private var fraction = savedFraction.get().coerceIn(MIN_FRACTION, MAX_FRACTION)

    init {
        build()
        (activity.findViewById<ViewGroup>(android.R.id.content)).addView(
            root,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        activity.onBackPressedDispatcher.addCallback(activity, back)
        activity.lifecycle.addObserver(this)
        permission = activity.activityResultRegistry.register(
            "jp_anki_permission",
            ActivityResultContracts.RequestPermission(),
        ) { granted -> onPermission(granted) }
        activity.lifecycleScope.launch {
            lookup.ankiAccess.refusals.collect(::onAnkiRefused)
        }
        park()
    }

    /**
     * Readies the popup for a quick first lookup in page theme [dark] (the reader of a Japanese novel,
     * once its engine is warm), without showing anything.
     */
    fun prewarm(dark: Boolean) {
        if (popup != null || showing) return
        this.dark = dark
        applyColors()
        val popup = obtainPopup() ?: return
        popup.prepare(dark)
    }

    /**
     * Looks [lookup] up in the closed sheet, ahead of a likely [show] of it (the reader's selection
     * toolbar offering "Look up"), so that [show] only has to open the sheet. Nothing if not ready.
     */
    fun preload(lookup: PopupLookup) {
        val popup = popup?.takeIf { !it.closed && it.ready && it.webView.parent === body } ?: return
        if (showing || lookup == preloaded || (lookup.dark != null && lookup.dark != dark)) return
        preloaded = lookup
        refusedEarly = null
        preloadedToken = popup.show(lookup)
    }

    /** Looks [lookup] up; [askedAt] (uptime) is when the reader asked, for the timing log. */
    fun show(lookup: PopupLookup, askedAt: Long = SystemClock.uptimeMillis()) {
        current = lookup
        this.askedAt = askedAt
        dark = lookup.dark ?: isNightMode()
        loading?.cancel()
        val popup = popup?.takeIf { !it.closed && it.webView.parent === body } ?: obtainPopup()
        if (popup != null && !showing && lookup == preloaded) {
            // Already looked up while the toolbar was up: only the sheet to open.
            preloaded = null
            token = preloadedToken
            applyColors()
            open()
            if (shownToken == token) whenPainted(popup, pageMillis = 0.0)
            return
        }
        preloaded = null
        refusedEarly = null
        val engine = this.lookup.engine
        // The lookup first, so Yomitan works while the sheet opens.
        if (popup != null && engine.state.value is YomitanEngine.State.Ready) token = popup.show(lookup)
        applyColors()
        open()
        if (popup == null) {
            showMessage(R.string.jp_lookup_failed)
            return
        }
        if (engine.state.value is YomitanEngine.State.Ready) {
            if (!popup.ready) showMessage(R.string.jp_lookup_starting, spinner = true)
            return
        }
        showMessage(R.string.jp_lookup_starting, spinner = true)
        loading = activity.lifecycleScope.launch {
            if (!engine.ready()) {
                showMessage(R.string.jp_lookup_failed)
                return@launch
            }
            token = popup.show(lookup)
        }
    }

    /** Closes the sheet (the popup stays ready for the next lookup). */
    fun close() {
        if (!showing) return
        showing = false
        back.isEnabled = false
        loading?.cancel()
        sheet.animate().translationY(sheet.height.toFloat()).setDuration(CLOSE_MILLIS)
            .setInterpolator(DecelerateInterpolator()).withEndAction {
                if (!showing) {
                    popup?.clear()
                    park()
                    onClosed()
                }
            }.start()
        scrim.animate().alpha(0f).setDuration(CLOSE_MILLIS).start()
    }

    override fun onResume(owner: LifecycleOwner) {
        // Another screen may have borrowed the popup meanwhile.
        val lookup = current
        if (showing && lookup != null && popup?.webView?.parent !== body) show(lookup, SystemClock.uptimeMillis())
    }

    override fun onDestroy(owner: LifecycleOwner) {
        loading?.cancel()
        permission?.unregister()
        popup?.let {
            it.listener = null
            lookup.releasePopup(it)
        }
        popup = null
    }

    // --- the popup --------------------------------------------------------------------------------

    private fun obtainPopup(): YomitanPopup? {
        val popup = lookup.popup(activity) ?: return null
        this.popup = popup
        popup.listener = object : YomitanPopup.Listener {
            override fun onShown(token: Int, pageMillis: Double, shownAt: Long) {
                shownToken = token
                if (token != this@LookupSheet.token || !showing) return
                hideMessage()
                whenPainted(popup, pageMillis)
            }

            override fun onCloseRequested() = close()

            override fun onGone() {
                this@LookupSheet.popup = null
                if (showing) current?.let { show(it, SystemClock.uptimeMillis()) }
            }
        }
        popup.webView.setBackgroundColor(if (dark) DARK_BACKGROUND else LIGHT_BACKGROUND)
        body.addView(popup.webView, 0, FrameLayout.LayoutParams(MATCH, MATCH))
        return popup
    }

    /** Logs the time from the press to the frame that shows the results (scripts read `lookup_shown`). */
    private fun whenPainted(popup: YomitanPopup, pageMillis: Double) {
        popup.webView.postVisualStateCallback(
            token.toLong(),
            object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    val ms = SystemClock.uptimeMillis() - askedAt
                    logcat(TAG, LogPriority.INFO) { "lookup_shown $ms ms (page ${pageMillis.roundToInt()} ms)" }
                }
            },
        )
    }

    // --- Anki -------------------------------------------------------------------------------------

    private fun onAnkiRefused(refusal: AnkiAccess.Refusal) {
        // A lookup made ahead of the sheet opening is refused before it opens.
        if (!showing) {
            refusedEarly = refusal
            return
        }
        when (refusal.status) {
            AnkiAccess.Status.NEEDS_PERMISSION -> showNotice(R.string.jp_anki_allow, R.string.jp_anki_allow_button) {
                permission?.launch(AnkiAccess.PERMISSION)
            }
            AnkiAccess.Status.MISSING_APP -> if (!lookup.ankiMissingNoted) {
                lookup.ankiMissingNoted = true
                showNotice(R.string.jp_anki_missing, R.string.jp_anki_missing_ok) { notice.visibility = View.GONE }
            }
            AnkiAccess.Status.READY -> Unit
        }
    }

    private fun onPermission(granted: Boolean) {
        if (granted) {
            notice.visibility = View.GONE
            // Yomitan shows its add buttons once it asks AnkiDroid again.
            current?.let { show(it, SystemClock.uptimeMillis()) }
        } else if (!activity.shouldShowRequestPermissionRationale(AnkiAccess.PERMISSION)) {
            showNotice(R.string.jp_anki_denied, R.string.jp_anki_open_settings) {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", activity.packageName, null),
                    ),
                )
            }
        }
    }

    private fun showNotice(text: Int, action: Int, onAction: () -> Unit) {
        noticeText.setText(text)
        noticeButton.setText(action)
        noticeButton.setOnClickListener { onAction() }
        notice.visibility = View.VISIBLE
    }

    // --- the sheet's views ------------------------------------------------------------------------

    private fun open() {
        val wasShowing = showing
        showing = true
        back.isEnabled = true
        root.parked = false
        root.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        scrim.visibility = View.VISIBLE
        if (wasShowing) return
        notice.visibility = View.GONE
        refusedEarly?.let(::onAnkiRefused)
        refusedEarly = null
        sheet.translationY = (sheet.height.takeIf { it > 0 } ?: root.height).toFloat()
        sheet.alpha = 1f
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(OPEN_MILLIS).start()
        sheet.animate().translationY(0f).setDuration(OPEN_MILLIS).setInterpolator(DecelerateInterpolator()).start()
    }

    /** At rest: laid out but transparent, taking no touches, the popup's page kept alive in it. */
    private fun park() {
        root.parked = true
        root.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        scrim.visibility = View.GONE
        notice.visibility = View.GONE
        hideMessage()
        sheet.animate().cancel()
        sheet.alpha = 0f
    }

    private fun showMessage(text: Int, spinner: Boolean = false) {
        message.setText(text)
        message.visibility = View.VISIBLE
        progress.visibility = if (spinner) View.VISIBLE else View.GONE
    }

    private fun hideMessage() {
        message.visibility = View.GONE
        progress.visibility = View.GONE
    }

    private fun isNightMode(): Boolean =
        activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun applyColors() {
        val background = if (dark) DARK_BACKGROUND else LIGHT_BACKGROUND
        val bar = if (dark) DARK_BAR else LIGHT_BAR
        val text = if (dark) DARK_TEXT else LIGHT_TEXT
        val radius = 16 * density
        sheet.background = GradientDrawable().apply {
            setColor(background)
            cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        }
        (handle.getChildAt(0).background as GradientDrawable).setColor(if (dark) DARK_PILL else LIGHT_PILL)
        (handle.getChildAt(1) as ImageButton).setColorFilter(text)
        notice.setBackgroundColor(bar)
        noticeText.setTextColor(text)
        noticeButton.setTextColor(if (dark) DARK_ACCENT else LIGHT_ACCENT)
        message.setTextColor(text)
        popup?.webView?.setBackgroundColor(background)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun build() {
        scrim.setBackgroundColor(SCRIM)
        scrim.setOnClickListener { close() }
        root.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))

        sheet.orientation = LinearLayout.VERTICAL
        // Touches on the sheet stay in it, never reaching the scrim behind (which closes it).
        sheet.isClickable = true
        sheet.clipToOutline = true
        sheet.elevation = 8 * density

        handle.contentDescription = activity.getString(R.string.jp_lookup_resize)
        handle.addView(
            View(activity).apply {
                background = GradientDrawable().apply { cornerRadius = 2 * density }
            },
            FrameLayout.LayoutParams(dp(36), dp(4), Gravity.CENTER),
        )
        handle.addView(
            ImageButton(activity).apply {
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                background = null
                contentDescription = activity.getString(R.string.jp_lookup_close)
                setOnClickListener { close() }
            },
            FrameLayout.LayoutParams(dp(48), dp(40), Gravity.END or Gravity.CENTER_VERTICAL),
        )
        handle.setOnTouchListener(Dragger())
        sheet.addView(handle, LinearLayout.LayoutParams(MATCH, dp(40)))

        notice.orientation = LinearLayout.HORIZONTAL
        notice.gravity = Gravity.CENTER_VERTICAL
        notice.setPadding(dp(16), dp(4), dp(8), dp(4))
        noticeText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        noticeButton.isAllCaps = false
        notice.addView(noticeText, LinearLayout.LayoutParams(0, WRAP, 1f))
        notice.addView(noticeButton, LinearLayout.LayoutParams(WRAP, WRAP))
        sheet.addView(notice, LinearLayout.LayoutParams(MATCH, WRAP))

        message.gravity = Gravity.CENTER
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        message.setPadding(dp(24), dp(56), dp(24), 0)
        body.addView(message, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
        body.addView(
            progress,
            FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin =
                    dp(8)
            },
        )
        hideMessage()
        sheet.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(sheet, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

        // Above the navigation bar and within the display's safe area.
        ViewCompat.setOnApplyWindowInsetsListener(sheet) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(0, 0, 0, bars.bottom)
            insets
        }
        applyColors()
    }

    private fun dp(value: Int) = (value * density).roundToInt()

    /** Full-window container; while parked it lets every touch through to the screen below. */
    private inner class Overlay : FrameLayout(activity) {
        var parked = true

        init {
            elevation = 24 * density
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean = if (parked) false else super.dispatchTouchEvent(ev)
    }

    /** The sheet: [fraction] of the window's height, at most [MAX_WIDTH_DP] wide. */
    private inner class Sheet : LinearLayout(activity) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = minOf(MeasureSpec.getSize(widthMeasureSpec), dp(MAX_WIDTH_DP))
            val height = (MeasureSpec.getSize(heightMeasureSpec) * fraction).roundToInt()
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
            )
        }
    }

    /** The handle: drag to resize, down (or flung down) to close. */
    private inner class Dragger : View.OnTouchListener {
        private var downY = 0f
        private var downFraction = 0f
        private var velocity: VelocityTracker? = null

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val height = root.height.takeIf { it > 0 } ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    downFraction = fraction
                    velocity = VelocityTracker.obtain().also { it.addMovement(event) }
                }
                MotionEvent.ACTION_MOVE -> {
                    velocity?.addMovement(event)
                    fraction = (downFraction - (event.rawY - downY) / height).coerceIn(MIN_DRAG_FRACTION, MAX_FRACTION)
                    sheet.requestLayout()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val tracker = velocity
                    tracker?.addMovement(event)
                    tracker?.computeCurrentVelocity(1000)
                    val flungDown = (tracker?.yVelocity ?: 0f) > FLING_DP_PER_SECOND * density
                    tracker?.recycle()
                    velocity = null
                    if (flungDown || fraction < CLOSE_FRACTION) {
                        close()
                        fraction = downFraction.coerceAtLeast(MIN_FRACTION)
                    } else {
                        fraction = fraction.coerceAtLeast(MIN_FRACTION)
                        savedFraction.set(fraction)
                        sheet.requestLayout()
                    }
                }
            }
            return true
        }
    }

    companion object {
        const val TAG = "JpLookup"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val MAX_WIDTH_DP = 640
        private const val MIN_FRACTION = 0.3f
        private const val MIN_DRAG_FRACTION = 0.1f
        private const val CLOSE_FRACTION = 0.22f
        private const val MAX_FRACTION = 0.92f
        private const val FLING_DP_PER_SECOND = 1200
        private const val OPEN_MILLIS = 180L
        private const val CLOSE_MILLIS = 150L

        // Yomitan's own popup colours (css/display.css), so the sheet and the page are one surface.
        private const val LIGHT_BACKGROUND = 0xFFFFFFFF.toInt()
        private const val DARK_BACKGROUND = 0xFF1E1E1E.toInt()
        private const val LIGHT_BAR = 0xFFF8F9FA.toInt()
        private const val DARK_BAR = 0xFF282828.toInt()
        private const val LIGHT_TEXT = 0xFF202124.toInt()
        private const val DARK_TEXT = 0xFFE8EAED.toInt()
        private const val LIGHT_PILL = 0xFFC4C7C5.toInt()
        private const val DARK_PILL = 0xFF5F6368.toInt()
        private const val LIGHT_ACCENT = 0xFF1A73E8.toInt()
        private const val DARK_ACCENT = 0xFF8AB4F8.toInt()
        private val SCRIM = Color.argb(40, 0, 0, 0)
    }
}
