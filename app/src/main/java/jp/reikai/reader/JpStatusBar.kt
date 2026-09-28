package jp.reikai.reader

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.os.BatteryManager
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.system.isNightMode
import jp.reikai.JpPreferences
import jp.reikai.yomitan.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import reikai.presentation.reader.NovelReaderViewModel
import reikai.presentation.reader.readerBackgroundColorInt
import reikai.presentation.reader.readerTextColorInt
import reikai.presentation.reader.resolvedForSystemTheme
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/**
 * The status bar of a novel reader (4.5, phase 4 ruling 13, after Tsundoku's): a thin bar at the bottom
 * with the clock and battery on the left, the chapter in the middle and the progress on the right; in the
 * Japanese reader also the chapter's characters before the page of its total (ttu's count) and this
 * session's reading speed. In the page's own colours, over the page and under nothing else of the reader:
 * it covers upstream's progress readout (the same number) and hides while the menu is open. On by default
 * in the Japanese reader, off in the standard one ([JpPreferences.readerStatusBar],
 * [JpPreferences.standardStatusBar]).
 *
 * Cheap by design: one View drawing three prepared strings, redrawn only when one changes; the clock
 * ticks once a minute, the battery comes from its sticky broadcast, and nothing runs while it is hidden
 * or the reader is in the background.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class JpStatusBar(
    private val host: ReaderActivity,
    private val viewModel: NovelReaderViewModel,
    private val preferences: JpPreferences,
) {
    private val bar = BarView(host)
    private val clockFormat = android.text.format.DateFormat.getTimeFormat(host)
    private val numbers = NumberFormat.getIntegerInstance()
    private var job: Job? = null
    private var shown = false
    private var receiverOn = false

    private var japanese = false
    private var clock = ""
    private var battery = ""
    private var percent = 0
    private var characters: Pair<Int, Int>? = null
    private var speed = 0L

    /** The bar's text row in dp, which the Japanese page keeps clear of text. */
    val heightDp: Int get() = ceil(bar.contentHeight / host.resources.displayMetrics.density).toInt()

    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            val now = System.currentTimeMillis()
            bar.postDelayed(this, MINUTE_MS - now % MINUTE_MS + 50)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onBattery(intent)
    }

    /**
     * Shows the bar as [isJapanese] (null while the novel's reader is not known) and its setting say,
     * and keeps it current until the reader ends.
     */
    fun start(isJapanese: StateFlow<Boolean?>) {
        val content = host.findViewById<ViewGroup>(android.R.id.content) ?: return
        content.addView(
            bar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
        bar.visibility = View.GONE
        // Its own insets: above visible system bars, clear of a cutout at either side.
        ViewCompat.setOnApplyWindowInsetsListener(bar) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }
        val enabled = isJapanese.flatMapLatest { which ->
            when (which) {
                null -> flowOf(null)
                true -> preferences.readerStatusBar().changes().map { if (it) true else null }
                false -> preferences.standardStatusBar().changes().map { if (it) false else null }
            }
        }
        // Shown only while the reader is started: the clock and the battery receiver stop in the background.
        val started = host.lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.STARTED) }
        job = combine(enabled, host.menuVisibility, started) { which, menu, visible -> Triple(which, menu, visible) }
            .distinctUntilChanged()
            .onEach { (which, menu, visible) ->
                japanese = which == true
                show(which != null && !menu && visible)
            }
            .launchIn(host.lifecycleScope)
        viewModel.chapter.onEach { bar.setTitle(it?.title.orEmpty()) }.launchIn(host.lifecycleScope)
        viewModel.progressPercent.onEach {
            percent = it
            updateRight()
        }.launchIn(host.lifecycleScope)
        viewModel.settings.onEach { settings ->
            val resolved = settings.resolvedForSystemTheme(host.isNightMode())
            val text = readerTextColorInt(resolved.textColor)
            bar.setColors(
                background = readerBackgroundColorInt(resolved.backgroundColor),
                text = ColorUtils.setAlphaComponent(text, TEXT_ALPHA),
            )
        }.launchIn(host.lifecycleScope)
    }

    /** A Japanese novel: its chapter titles drawn with Japanese glyph forms, whatever the device's language. */
    fun setJapaneseText() = bar.setTextLocale(Locale.JAPANESE)

    /** The Japanese page's place: [charOffset] characters before the page, of the chapter's [chars]. */
    fun setPage(charOffset: Int, chars: Int) {
        characters = charOffset to chars
        updateRight()
    }

    /** This session's reading speed in characters an hour (ttu's), 0 before any. */
    fun setSpeed(charactersPerHour: Long) {
        if (speed == charactersPerHour) return
        speed = charactersPerHour
        updateRight()
    }

    fun close() {
        show(false)
        job?.cancel()
        (bar.parent as? ViewGroup)?.removeView(bar)
    }

    private fun show(visible: Boolean) {
        if (visible == shown) {
            if (visible) updateRight()
            return
        }
        shown = visible
        if (visible) {
            updateRight()
            clockTick.run()
            // Sticky: the current level comes back at once.
            ContextCompat.registerReceiver(
                host,
                batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )?.let(::onBattery)
            receiverOn = true
            bar.visibility = View.VISIBLE
        } else {
            bar.visibility = View.GONE
            bar.removeCallbacks(clockTick)
            if (receiverOn) runCatching { host.unregisterReceiver(batteryReceiver) }
            receiverOn = false
        }
    }

    private fun updateClock() {
        val text = clockFormat.format(Date())
        if (text == clock) return
        clock = text
        updateLeft()
    }

    private fun onBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val text = host.getString(
            if (charging) R.string.jp_status_charging else R.string.jp_status_battery,
            level * 100 / scale,
        )
        if (text == battery) return
        battery = text
        updateLeft()
    }

    private fun updateLeft() = bar.setLeft("$clock$GAP$battery")

    private fun updateRight() {
        if (!shown) return
        val progress = host.getString(R.string.jp_status_progress, percent)
        val page = characters
        bar.setRight(
            if (japanese && page != null) {
                val read = host.getString(
                    R.string.jp_status_characters,
                    numbers.format(page.first),
                    numbers.format(page.second),
                )
                val perHour = host.getString(R.string.jp_status_speed, numbers.format(speed))
                "$read$GAP$perHour$GAP$progress"
            } else {
                progress
            },
        )
    }

    /** Three strings in one row, measured when they change and only drawn in [onDraw]. */
    @SuppressLint("ViewConstructor")
    private class BarView(context: Context) : View(context) {
        private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEXT_SP, resources.displayMetrics)
        }

        /** Read once: the text size never changes, and reading them allocates. */
        private val metrics = paint.fontMetrics
        private val gapPx = dp(12f)
        private val sidePx = dp(12f)
        private val verticalPx = dp(4f)
        private var background = 0
        private var left = ""
        private var right = ""
        private var title = ""
        private var shownTitle: CharSequence = ""
        private var leftWidth = 0f
        private var rightWidth = 0f
        private var titleWidth = 0f

        /** The text row with its margins, without the insets below it. */
        val contentHeight: Float
            get() = metrics.descent - metrics.ascent + 2 * verticalPx

        init {
            // Not a target: taps go to the page under it as if the bar were not there.
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun setTextLocale(locale: Locale) {
            if (paint.textLocale == locale) return
            paint.textLocale = locale
            leftWidth = paint.measureText(left)
            rightWidth = paint.measureText(right)
            fitTitle()
        }

        fun setColors(background: Int, text: Int) {
            if (this.background == background && paint.color == text) return
            this.background = background
            paint.color = text
            invalidate()
        }

        fun setLeft(text: String) {
            if (text == left) return
            left = text
            leftWidth = paint.measureText(text)
            fitTitle()
        }

        fun setRight(text: String) {
            if (text == right) return
            right = text
            rightWidth = paint.measureText(text)
            fitTitle()
        }

        fun setTitle(text: String) {
            if (text == title) return
            title = text
            fitTitle()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = fitTitle()

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val height = ceil(contentHeight).toInt() + paddingTop + paddingBottom
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
        }

        /** The chapter in what is left between the two sides, cut with an ellipsis, centred where it fits. */
        private fun fitTitle() {
            val start = paddingLeft + sidePx + leftWidth + gapPx
            val end = width - paddingRight - sidePx - rightWidth - gapPx
            val room = max(0f, end - start)
            shownTitle = TextUtils.ellipsize(title, paint, room, TextUtils.TruncateAt.END)
            titleWidth = paint.measureText(shownTitle, 0, shownTitle.length)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(background)
            val baseline = paddingTop + verticalPx - metrics.ascent
            canvas.drawText(left, paddingLeft + sidePx, baseline, paint)
            canvas.drawText(right, width - paddingRight - sidePx - rightWidth, baseline, paint)
            if (shownTitle.isNotEmpty()) {
                val start = paddingLeft + sidePx + leftWidth + gapPx
                val end = width - paddingRight - sidePx - rightWidth - gapPx
                val centred = (width - titleWidth) / 2
                val x = centred.coerceIn(start, max(start, end - titleWidth))
                canvas.drawText(shownTitle, 0, shownTitle.length, x, baseline, paint)
            }
        }

        private fun dp(value: Float) = value * resources.displayMetrics.density
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val TEXT_SP = 12f
        const val TEXT_ALPHA = 0xB3
        const val GAP = "   "
    }
}
