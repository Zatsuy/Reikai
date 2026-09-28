package jp.reikai.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.Gravity
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import jp.reikai.di.jpGraph
import jp.reikai.lookup.lookupOffCard
import jp.reikai.lookup.lookupOffCardParams
import jp.reikai.yomitan.PageKind
import jp.reikai.yomitan.R
import jp.reikai.yomitan.YomitanDownload
import jp.reikai.yomitan.YomitanOrigin
import jp.reikai.yomitan.YomitanPage
import jp.reikai.yomitan.YomitanPageListener
import jp.reikai.yomitan.settings.YomitanDictionaries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * Yomitan's own settings (`settings.html`), and its other pages (info, legal), full screen: "All
 * Yomitan settings" and the sections the Japanese settings open directly (roadmap 3.5), and where
 * Yomitan's own links to its settings lead (the lookup sheet's "no dictionaries" notice).
 *
 * What a browser gives the page and a WebView does not: its file inputs (dictionary zips, a settings
 * backup) open the system file picker, and a file it saves (a settings export) goes through the
 * system's "save as" screen. A section opens scrolled into view once the page has prepared itself,
 * and "Get recommended dictionaries" opens Yomitan's own list of them, also when a "no dictionaries"
 * notice led here and none is installed. While lookup is off it says so and offers to turn it on.
 */
class YomitanSettingsActivity : ComponentActivity() {

    private val lookup by lazy { jpGraph.jpLookup }
    private lateinit var frame: FrameLayout
    private var webView: WebView? = null
    private var page: YomitanPage? = null

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val pickOne = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        answerFiles(uri?.let { arrayOf(it) })
    }
    private val pickMany = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        answerFiles(uris.takeIf { it.isNotEmpty() }?.toTypedArray())
    }

    /** The file waiting for the user's "save as" choice; kept across a recreation behind the picker. */
    private var pendingSave: YomitanDownload? = null
    private val saveAs = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val target = result.data?.data?.takeIf { result.resultCode == RESULT_OK }
        val save = pendingSave
        pendingSave = null
        if (save != null) {
            finishSave(save, target)
        } else if (target != null) {
            // The file went with the app while the picker was open: the picker's new file is empty.
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    runCatching { DocumentsContract.deleteDocument(contentResolver, target) }
                }
                Toast.makeText(this@YomitanSettingsActivity, R.string.jp_yomitan_save_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingSave = savedInstanceState?.let(::restoreSave)
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
        open()
    }

    private fun open() {
        frame.removeAllViews()
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
        val path = intent.getStringExtra(EXTRA_PATH) ?: SETTINGS
        val web = WebView(this)
        val page = lookup.engine.attach(web, PageKind.SETTINGS, this, Listener(path)) ?: return
        webView = web
        this.page = page
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
            if (ready) page.load(path)
        }
    }

    private inner class Listener(private val path: String) : YomitanPageListener {
        private var arranged = false

        override fun onPageFinished(url: String) {
            if (arranged || !url.startsWith(YomitanOrigin.url(SETTINGS))) return
            arranged = true
            val section = intent.getStringExtra(EXTRA_SECTION) ?: path.substringAfter('#', "").ifEmpty { null }
            val recommend = intent.getBooleanExtra(EXTRA_RECOMMENDED, false)
            lifecycleScope.launch {
                // Yomitan's "no dictionaries" notice links to its dictionaries section: with none
                // installed, its list of recommended ones is where to go.
                val open = recommend ||
                    (
                        section == DICTIONARIES &&
                            runCatching { YomitanDictionaries.installed(lookup.engine) }.getOrNull()?.isEmpty() == true
                        )
                webView?.evaluateJavascript(arrange(section, open), null)
            }
        }

        override fun onRenderProcessGone() {
            webView = null
            page = null
            if (!isFinishing) open()
        }

        override fun onShowFileChooser(
            callback: ValueCallback<Array<Uri>>,
            params: WebChromeClient.FileChooserParams,
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            // Every file: a desktop backup copied to the tablet may not be labelled as JSON, nor a
            // dictionary as a zip; Yomitan checks what it gets.
            val types = arrayOf("*/*")
            if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                pickMany.launch(types)
            } else {
                pickOne.launch(types)
            }
            return true
        }

        override fun onDownload(download: YomitanDownload): Boolean {
            // One "save as" at a time: a second would take over the first one's chosen file.
            if (pendingSave != null) return false
            pendingSave = download
            saveAs.launch(
                Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(download.mimeType)
                    .putExtra(Intent.EXTRA_TITLE, download.name),
            )
            return true
        }
    }

    private fun answerFiles(uris: Array<Uri>?) {
        fileCallback?.onReceiveValue(uris)
        fileCallback = null
    }

    private fun finishSave(download: YomitanDownload, target: Uri?) {
        lifecycleScope.launch {
            val saved = target != null && withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(target)!!.use { out ->
                        download.file.inputStream().use { it.copyTo(out) }
                    }
                }.onFailure { logcat(LogPriority.WARN, it) { "Yomitan settings: saving ${download.name}" } }.isSuccess
            }
            withContext(Dispatchers.IO) { download.file.delete() }
            if (target != null) {
                val text = if (saved) {
                    getString(
                        R.string.jp_yomitan_saved,
                        download.name,
                    )
                } else {
                    getString(R.string.jp_yomitan_save_failed)
                }
                Toast.makeText(this@YomitanSettingsActivity, text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingSave?.let {
            outState.putString(STATE_SAVE_NAME, it.name)
            outState.putString(STATE_SAVE_TYPE, it.mimeType)
            outState.putString(STATE_SAVE_FILE, it.file.path)
        }
    }

    private fun restoreSave(state: Bundle): YomitanDownload? {
        val file = state.getString(STATE_SAVE_FILE)?.let(::File)?.takeIf { it.isFile } ?: return null
        return YomitanDownload(
            state.getString(STATE_SAVE_NAME) ?: file.name,
            state.getString(STATE_SAVE_TYPE) ?: "*/*",
            file,
        )
    }

    override fun onDestroy() {
        answerFiles(null)
        // Recreated behind the picker, the new screen takes the file over (onSaveInstanceState).
        if (!isChangingConfigurations) pendingSave?.file?.delete()
        pendingSave = null
        page?.close()
        page = null
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_PATH = "path"
        private const val EXTRA_SECTION = "section"
        private const val EXTRA_RECOMMENDED = "recommended"
        private const val SETTINGS = "settings.html"
        private const val STATE_SAVE_NAME = "jp_save_name"
        private const val STATE_SAVE_TYPE = "jp_save_type"
        private const val STATE_SAVE_FILE = "jp_save_file"

        /** Yomitan's settings sections the Japanese settings open (`<h2 id>` in `settings.html`). */
        const val DICTIONARIES = "dictionaries"
        const val ANKI = "anki"
        const val BACKUP = "backup"

        /** Yomitan's settings, scrolled to [section]; with [recommended], its recommended dictionaries open. */
        fun intent(context: Context, section: String? = null, recommended: Boolean = false): Intent =
            Intent(context, YomitanSettingsActivity::class.java)
                .putExtra(EXTRA_SECTION, section)
                .putExtra(EXTRA_RECOMMENDED, recommended)

        /** One of Yomitan's pages by its engine URL (`settings.html#dictionaries`, `info.html`). */
        fun pageIntent(context: Context, url: String): Intent = Intent(context, YomitanSettingsActivity::class.java)
            .putExtra(EXTRA_PATH, url.removePrefix(YomitanOrigin.ORIGIN).removePrefix("/").ifEmpty { SETTINGS })

        /**
         * Once Yomitan's settings page has prepared itself (it shows its body only then), scrolls to
         * [section] and opens the recommended dictionaries with the page's own button. Uses only the
         * page's markup (ids and the button's attribute, fingerprinted by the update tripwire).
         */
        private fun arrange(section: String?, recommended: Boolean): String {
            val id = section?.filter { it.isLetterOrDigit() || it == '-' }.orEmpty()
            return """
                (() => {
                    const go = () => {
                        const heading = ${if (id.isEmpty()) "null" else "document.getElementById('$id')"};
                        if (heading) { heading.scrollIntoView({block: 'start'}); }
                        if ($recommended) {
                            document.querySelector('[data-modal-action="show,recommended-dictionaries"]')?.click();
                        }
                    };
                    if (!document.body.hidden) { go(); return; }
                    const watch = new MutationObserver(() => {
                        if (document.body.hidden) { return; }
                        watch.disconnect();
                        setTimeout(go, 0);
                    });
                    watch.observe(document.body, {attributes: true, attributeFilter: ['hidden']});
                })();
            """.trimIndent()
        }
    }
}
