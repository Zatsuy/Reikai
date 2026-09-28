package jp.reikai.lookup

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import jp.reikai.yomitan.R
import kotlin.math.roundToInt

/** Where [lookupOffCard] goes: at the bottom, within thumb reach, and no wider than the lookup sheet. */
internal fun lookupOffCardParams(context: Context): FrameLayout.LayoutParams {
    val metrics = context.resources.displayMetrics
    val width = minOf(metrics.widthPixels, (MAX_WIDTH_DP * metrics.density).roundToInt())
    return FrameLayout.LayoutParams(
        width,
        FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
    )
}

private const val MAX_WIDTH_DP = 640

/**
 * "Dictionary lookup is off", with Close and Turn on (D-025): what the lookup screens show instead of
 * a dictionary while lookup is switched off.
 */
internal fun lookupOffCard(context: Context, onClose: () -> Unit, onTurnOn: () -> Unit): View {
    val density = context.resources.displayMetrics.density
    fun dp(value: Int) = (value * density).roundToInt()
    val dark =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val text = if (dark) 0xFFE8EAED.toInt() else 0xFF202124.toInt()
    val accent = if (dark) 0xFF8AB4F8.toInt() else 0xFF1A73E8.toInt()
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(20), dp(16), dp(12))
        isClickable = true
        background = GradientDrawable().apply {
            setColor(if (dark) 0xFF1E1E1E.toInt() else 0xFFFFFFFF.toInt())
            val radius = 16 * density
            cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        }
        addView(
            TextView(context).apply {
                setText(R.string.jp_lookup_off_title)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(text)
            },
        )
        addView(
            TextView(context).apply {
                setText(R.string.jp_lookup_off_body)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(text)
                setPadding(0, dp(8), dp(8), dp(8))
            },
        )
        addView(
            LinearLayout(context).apply {
                gravity = Gravity.END
                addView(
                    Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                        isAllCaps = false
                        setText(R.string.jp_lookup_close)
                        setTextColor(accent)
                        setOnClickListener { onClose() }
                    },
                )
                addView(
                    Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                        isAllCaps = false
                        setText(R.string.jp_lookup_turn_on)
                        setTextColor(accent)
                        setOnClickListener { onTurnOn() }
                    },
                )
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
    }
}
