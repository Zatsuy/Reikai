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
 * The lookup sheet: Yomitan's results ([YomitanPopup]) in a sheet over [activity]'s content, the one
 * popup of every surface. It opens at the bottom, or at the top when it would cover the word looked
 * up. Drag its handle to resize it or away to close it; a tap outside, the back gesture (once
 * Yomitan's own history has nothing to go back to) or Yomitan's close button close it too. At most
 * about 640 dp wide, so it reads well on a tablet, and in the theme of the page it looks up from.
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
        override fun handleOnBackPressed() {
            // A word tapped in the results opened in the sheet: back goes to the one before.
            val popup = popup
            if (popup != null && popup.canGoBack) popup.goBack() else close()
        }
    }

    /** The sheet sits at the top of the window (the word looked up is in the part it would cover). */
    private var atTop = false

    /** The window's bars and cutout, which the sheet keeps clear of on its screen edge. */
    private var barsTop = 0
    private var barsBottom = 0

    var showing = false
        private set

    /** The app's system settings were opened from the sheet, and its screen has not been back since. */
    var inSettings = false
        private set

    /** The share of the window's height the sheet takes; the reader's last choice is kept. */
    private val savedFraction = lookup.sheetHeight()
    private var fraction = savedFraction.get().coerceIn(MIN_FRACTION, MAX_FRACTION)

    /** The height a drag that closed the sheet left for its next opening, until it takes it. */
    private var nextFraction: Float? = null

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
        if (popup?.closed == false || showing) return
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

    /**
     * Looks [lookup] up; [askedAt] (uptime) is when the reader asked, for the timing log. [word] is
     * where the word is on the screen (its top to bottom, in screen pixels), if known: the sheet then
     * opens where it leaves the word in view.
     */
    fun show(lookup: PopupLookup, askedAt: Long = SystemClock.uptimeMillis(), word: IntRange? = null) {
        current = lookup
        this.askedAt = askedAt
        dark = lookup.dark ?: isNightMode()
        loading?.cancel()
        if (!showing) {
            restoreFraction()
            place(word)
        }
        val popup = popup?.takeIf { !it.closed && it.webView.parent === body } ?: obtainPopup()
        if (popup != null && !showing && lookup == preloaded && popup.holds(preloadedToken)) {
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

    /**
     * Closes the sheet (the popup stays ready for the next lookup); [thenFraction] is its height for
     * the next time, when a drag left it smaller.
     */
    fun close(thenFraction: Float? = null) {
        if (!showing) return
        showing = false
        back.isEnabled = false
        loading?.cancel()
        // Not left to the animation's end, which a lookup during the animation cancels.
        if (thenFraction != null) nextFraction = thenFraction
        sheet.animate().translationY(offScreen()).setDuration(CLOSE_MILLIS)
            .setInterpolator(DecelerateInterpolator()).withEndAction {
                if (!showing) {
                    // Laid out again while parked, so the next lookup opens at full height.
                    restoreFraction()
                    popup?.clear()
                    park()
                    onClosed()
                }
            }.start()
        scrim.animate().alpha(0f).setDuration(CLOSE_MILLIS).start()
    }

    override fun onResume(owner: LifecycleOwner) {
        inSettings = false
        // Another screen may have borrowed the popup meanwhile.
        val lookup = current
        if (showing && lookup != null && popup?.webView?.parent !== body) show(lookup, SystemClock.uptimeMillis())
    }

    override fun onDestroy(owner: LifecycleOwner) {
        loading?.cancel()
        permission?.unregister()
        popup?.let {
            // Another screen may have taken the popup over since: it is then that screen's.
            if (it.listener === popupListener) it.listener = null
            lookup.releasePopup(it, activity)
        }
        popup = null
    }

    // --- the popup --------------------------------------------------------------------------------

    private val popupListener = object : YomitanPopup.Listener {
        override fun onShown(token: Int, pageMillis: Double, shownAt: Long) {
            shownToken = token
            val popup = popup ?: return
            if (token != this@LookupSheet.token || !showing) return
            hideMessage()
            whenPainted(popup, pageMillis)
        }

        override fun onCloseRequested() = close()

        override fun onGone() {
            popup = null
            preloaded = null
            if (showing) current?.let { show(it, SystemClock.uptimeMillis()) }
        }
    }

    private fun obtainPopup(): YomitanPopup? {
        val popup = lookup.popup(activity) ?: return null
        this.popup = popup
        popup.listener = popupListener
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
                inSettings = true
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
        sheet.translationY = offScreen()
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

    private fun restoreFraction() {
        val next = nextFraction ?: return
        nextFraction = null
        fraction = next
        sheet.requestLayout()
    }

    /**
     * The height the sheet takes its share of: the overlay's, or before the overlay's first layout (a
     * lookup just after the reader opened) the view it fills.
     */
    private fun windowHeight(): Int = root.height.takeIf { it > 0 }
        ?: (root.parent as? View)?.let { it.height - it.paddingTop - it.paddingBottom }
        ?: 0

    /** Just past the sheet's screen edge, where it slides in from and out to (at its coming height). */
    private fun offScreen(): Float {
        val height = windowHeight() * fraction
        return if (atTop) -height else height
    }

    /**
     * Puts the sheet at the bottom, or at the top when at the bottom it would cover the [word] (screen
     * pixels, top to bottom) and at the top it would not, or would cover less of the page around it.
     */
    private fun place(word: IntRange?) {
        val height = windowHeight()
        val top = if (word == null || height <= 0) {
            false
        } else {
            val origin = IntArray(2).also(root::getLocationOnScreen)[1]
            SheetPlacement.atTop(word.first - origin, word.last - origin, height, (height * fraction).roundToInt())
        }
        if (top == atTop) return
        atTop = top
        (sheet.layoutParams as FrameLayout.LayoutParams).gravity =
            (if (top) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
        // The handle on the edge that faces the page.
        sheet.removeView(handle)
        sheet.addView(handle, if (top) sheet.childCount else 0, LinearLayout.LayoutParams(MATCH, dp(HANDLE_DP)))
        applyInsets()
        applyColors()
        sheet.requestLayout()
    }

    private fun applyInsets() {
        sheet.setPadding(0, if (atTop) barsTop else 0, 0, if (atTop) 0 else barsBottom)
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
            // Rounded on the edge that faces the page.
            cornerRadii = if (atTop) {
                floatArrayOf(0f, 0f, 0f, 0f, radius, radius, radius, radius)
            } else {
                floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
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
        sheet.addView(handle, LinearLayout.LayoutParams(MATCH, dp(HANDLE_DP)))

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

        // Clear of the system bars and within the display's safe area, on the sheet's screen edge.
        ViewCompat.setOnApplyWindowInsetsListener(sheet) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            barsTop = bars.top
            barsBottom = bars.bottom
            applyInsets()
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

    /** The handle: drag to resize, away from the page (or flung that way) to close. */
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
                    // Towards the page grows the sheet: down for one at the top, up for one at the bottom.
                    val grown = (if (atTop) event.rawY - downY else downY - event.rawY) / height
                    fraction = (downFraction + grown).coerceIn(MIN_DRAG_FRACTION, MAX_FRACTION)
                    sheet.requestLayout()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val tracker = velocity
                    tracker?.addMovement(event)
                    tracker?.computeCurrentVelocity(1000)
                    val towardsEdge = (tracker?.yVelocity ?: 0f) * (if (atTop) -1 else 1)
                    val flungAway = towardsEdge > FLING_DP_PER_SECOND * density
                    tracker?.recycle()
                    velocity = null
                    if (flungAway || fraction < CLOSE_FRACTION) {
                        close(thenFraction = downFraction.coerceAtLeast(MIN_FRACTION))
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
        private const val HANDLE_DP = 40
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
