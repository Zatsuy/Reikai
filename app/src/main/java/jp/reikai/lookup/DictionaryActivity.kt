package jp.reikai.lookup

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import jp.reikai.di.jpGraph
import jp.reikai.yomitan.PageKind
import jp.reikai.yomitan.YomitanPage
import jp.reikai.yomitan.YomitanPageListener
import kotlinx.coroutines.launch
import java.net.URLEncoder

/**
 * The dictionary search screen: Yomitan's own search page (`search.html`), full screen. Opened by the
 * launcher's "Dictionary" shortcut, by Yomitan's own links to its search page, and by
 * [JpLookup.searchIntent] (the Japanese settings, 3.5). With nothing to look up yet, the keyboard
 * opens on the search box, so it never needs reaching for at the top of a tablet. While lookup is
 * off it says so and offers to turn it on (D-025).
 */
class DictionaryActivity : ComponentActivity() {

    private val lookup by lazy { jpGraph.jpLookup }
    private lateinit var frame: FrameLayout
    private var webView: WebView? = null
    private var page: YomitanPage? = null

    /** The latest thing asked to look up; one asked while the engine starts is looked up once it is ready. */
    private var query: String? = null

    /** The page is loaded (the engine was ready): a new query loads at once. */
    private var loaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        frame = FrameLayout(this)
        setContentView(frame)
        ViewCompat.setOnApplyWindowInsetsListener(frame) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val web = webView
                    if (web != null && web.canGoBack()) web.goBack() else finish()
                }
            },
        )
        query = intent.getStringExtra(EXTRA_QUERY)
        open()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        query = intent.getStringExtra(EXTRA_QUERY)
        val page = page
        when {
            page == null -> open()
            loaded -> page.load(searchPath(query))
            // Still waiting for the engine: the page loads with this query then.
            else -> Unit
        }
    }

    private fun open() {
        frame.removeAllViews()
        loaded = false
        if (!lookup.isEnabled) {
            frame.addView(
                lookupOffCard(this, onClose = ::finish) {
                    lookup.setEnabled(true)
                    open()
                },
                lookupOffCardParams(this),
            )
            return
        }
        val web = WebView(this)
        val page = lookup.engine.attach(
            web,
            PageKind.SEARCH,
            this,
            object : YomitanPageListener {
                override fun onPageFinished(url: String) {
                    if (query.isNullOrEmpty()) showKeyboard(web)
                }

                override fun onRenderProcessGone() {
                    webView = null
                    this@DictionaryActivity.page = null
                    if (!isFinishing) open()
                }
            },
        ) ?: return
        webView = web
        this.page = page
        web.setBackgroundColor(0)
        frame.addView(
            web,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        val progress = ProgressBar(this)
        frame.addView(
            progress,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        lifecycleScope.launch {
            val ready = lookup.engine.ready()
            frame.removeView(progress)
            if (ready && this@DictionaryActivity.page === page) {
                loaded = true
                page.load(searchPath(query))
            }
        }
    }

    private fun showKeyboard(web: WebView) {
        web.requestFocus()
        web.postDelayed({
            getSystemService(InputMethodManager::class.java)?.showSoftInput(web, 0)
        }, KEYBOARD_DELAY_MILLIS)
    }

    override fun onDestroy() {
        page?.close()
        page = null
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_QUERY = "query"
        private const val KEYBOARD_DELAY_MILLIS = 150L

        fun intent(context: Context, query: String? = null): Intent =
            Intent(context, DictionaryActivity::class.java).putExtra(EXTRA_QUERY, query)

        private fun searchPath(query: String?): String =
            if (query.isNullOrEmpty()) {
                "search.html"
            } else {
                "search.html?query=" +
                    URLEncoder.encode(query, Charsets.UTF_8)
            }
    }
}
