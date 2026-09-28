package jp.reikai.translate

import jp.reikai.yomitan.text.JapaneseText
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.util.Collections
import java.util.IdentityHashMap

/**
 * A chapter's translation in place (4.5, phase 4 ruling 15), on the markup the reader's content
 * pipeline produced: readings (`rt`, `rp`) go, the text is cut into paragraphs where the markup breaks
 * it (block elements and `br`), each paragraph goes to the engine, and each translation goes back
 * where its paragraph was, so pictures, links and the chapter's structure stay. Every translation
 * sits in an element with the target language as `lang` (so a Japanese chapter beside it in one page
 * keeps `ja`), and the document starts with a marker naming it ([languageOf]), which the Japanese
 * reader reads to lay a translation out horizontally.
 */
object ChapterTranslator {

    /** A chapter parsed and cut into paragraphs, ready to translate or to take a translation. */
    class Prepared internal constructor(internal val document: Document, internal val segments: List<Segment>) {
        val texts: List<String> = segments.map { it.text }

        /** The chapter's text for the cache: the same paragraphs in the same order, whatever markup holds them. */
        val hash: String by lazy { shortHash(texts.joinToString("\u0001")) }

        /** `ja` when the chapter reads as Japanese, else `auto` for the engine to detect. */
        val source: String get() = if (JapaneseText.looksJapanese(texts.joinToString("\n"))) "ja" else "auto"
    }

    /** A paragraph: its text and the nodes of [container] holding it. */
    internal class Segment(val container: Element, val nodes: List<Node>, val text: String)

    fun prepare(html: String): Prepared {
        val document = Jsoup.parseBodyFragment(html)
        document.outputSettings().prettyPrint(false)
        document.select("rt, rp").remove()
        val segments = ArrayList<Segment>()
        collect(document.body(), segments)
        return Prepared(document, segments)
    }

    /**
     * Translations of [prepared]'s paragraphs, in batches the engine takes; each batch must come back
     * with as many translations as it sent, or the whole translation fails.
     */
    suspend fun translate(prepared: Prepared, engine: TranslationEngine, target: String): List<String> {
        val texts = prepared.texts
        val source = prepared.source
        val out = ArrayList<String>(texts.size)
        for (range in batches(texts, engine.maxTexts, engine.maxChars)) {
            val batch = texts.subList(range.first, range.last + 1)
            val translated = engine.translate(batch, source, target)
            if (translated.size != batch.size) {
                throw TranslationFailure("${engine.name}: ${translated.size} translations for ${batch.size} paragraphs")
            }
            out += translated
        }
        return out
    }

    /** Consecutive runs of at most [maxTexts] texts and about [maxChars] characters. */
    fun batches(texts: List<String>, maxTexts: Int, maxChars: Int): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = 0
        var chars = 0
        texts.forEachIndexed { i, text ->
            val count = i - start
            if (count > 0 && (count >= maxTexts || chars + text.length > maxChars)) {
                out += start until i
                start = i
                chars = 0
            }
            chars += text.length
        }
        if (start < texts.size) out += start until texts.size
        return out
    }

    /**
     * The chapter with [translations] in place of its paragraphs, marked as a translation into
     * [language]. A blank translation leaves its paragraph as it was. [prepared] is used up.
     */
    fun write(prepared: Prepared, translations: List<String>, language: String): String {
        if (translations.size != prepared.segments.size) {
            throw TranslationFailure("${translations.size} translations for ${prepared.segments.size} paragraphs")
        }
        val body = prepared.document.body()
        val touched: MutableSet<Element> = Collections.newSetFromMap(IdentityHashMap())
        prepared.segments.forEachIndexed { i, segment ->
            val translation = translations[i].trim()
            if (translation.isEmpty()) return@forEachIndexed
            // Text straight in the body gets an element to carry its language.
            val span = if (segment.container ===
                body
            ) {
                Element("span").attr("lang", language).text(translation)
            } else {
                null
            }
            replace(segment, span ?: TextNode(translation))
            if (span == null) touched.add(segment.container)
        }
        touched.forEach { it.attr("lang", language) }
        return marker(language) + body.html()
    }

    /** The language a chapter was translated into by [write], or null for a chapter as its source gave it. */
    fun languageOf(html: String): String? {
        if (!html.startsWith(MARKER_START)) return null
        val end = html.indexOf(MARKER_END, MARKER_START.length).takeIf { it > 0 } ?: return null
        return html.substring(MARKER_START.length, end).takeIf(LANGUAGE::matches)
    }

    private fun marker(language: String): String {
        require(LANGUAGE.matches(language)) { "Not a language code: $language" }
        return MARKER_START + language + MARKER_END
    }

    private const val MARKER_START = "<!--reikai-translation:"
    private const val MARKER_END = "-->"
    private val LANGUAGE = Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*")

    // --- Paragraphs --------------------------------------------------------------------------------

    /**
     * The paragraphs of [container]: runs of its text and inline children, cut at `br` and at block
     * children, which hold paragraphs of their own.
     */
    private fun collect(container: Element, out: MutableList<Segment>) {
        val run = ArrayList<Node>()
        fun flush() {
            if (run.isEmpty()) return
            val text = normalize(run.joinToString("") { textOf(it) })
            if (text.isNotEmpty()) out += Segment(container, run.toList(), text)
            run.clear()
        }
        for (child in container.childNodes()) {
            when (child) {
                is TextNode -> run.add(child)
                is Element -> when {
                    child.normalName() in SKIPPED || child.normalName() == "br" -> flush()
                    isInline(child) -> run.add(child)
                    else -> {
                        flush()
                        collect(child, out)
                    }
                }
                // Comments and the like stay where they are.
                else -> Unit
            }
        }
        flush()
    }

    private fun isInline(element: Element): Boolean =
        element.normalName() in INLINE && element.select(BREAKS).isEmpty()

    private fun textOf(node: Node): String = when (node) {
        is TextNode -> node.wholeText
        is Element -> node.wholeText()
        else -> ""
    }

    /** Line breaks and runs of spaces in the markup are one space; a full-width space inside stays. */
    private fun normalize(text: String): String = WHITESPACE.replace(text, " ").trim()

    private val WHITESPACE = Regex("[\\s ]+")

    /**
     * [translation] (its text, or a span holding it) where [segment]'s nodes were. Pictures and other media inside them stay: those
     * before the paragraph's first text before the translation, the others after it.
     */
    private fun replace(segment: Segment, translation: Node) {
        val before = ArrayList<Element>()
        val after = ArrayList<Element>()
        var seenText = false
        for (node in segment.nodes) {
            if (node is TextNode) {
                if (!node.isBlank) seenText = true
                continue
            }
            val element = node as? Element ?: continue
            val media = if (element.normalName() in
                MEDIA
            ) {
                listOf(element)
            } else {
                outermost(element.select(MEDIA_SELECTOR))
            }
            (if (seenText) after else before).addAll(media)
            if (element.hasText()) seenText = true
        }
        val kept: MutableSet<Node> = Collections.newSetFromMap(IdentityHashMap())
        kept.addAll(before)
        kept.addAll(after)
        val text = translation
        segment.nodes.first().before(text)
        before.forEach { text.before(it) }
        var last: Node = text
        after.forEach {
            last.after(it)
            last = it
        }
        segment.nodes.forEach { if (it !in kept && it.parentNode() != null) it.remove() }
    }

    /** A `picture` and not also its own `img`, which would be pulled out of it. */
    private fun outermost(found: List<Element>): List<Element> {
        val set: MutableSet<Element> = Collections.newSetFromMap(IdentityHashMap())
        set.addAll(found)
        return found.filter { element -> element.parents().none { it in set } }
    }

    /** Never text to translate: code, forms, scripts and their like. */
    private val SKIPPED = setOf(
        "script", "style", "noscript", "template", "svg", "math", "textarea", "select", "iframe", "object",
        "embed", "canvas", "head", "title", "pre",
    )

    /** Inline elements, part of their parent's paragraph unless they hold a break or a block. */
    private val INLINE = setOf(
        "a", "abbr", "acronym", "audio", "b", "bdi", "bdo", "big", "cite", "code", "data", "del", "dfn", "em",
        "font", "i", "img", "ins", "kbd", "label", "mark", "nobr", "picture", "q", "rb", "rtc", "ruby", "s",
        "samp", "small", "source", "span", "strike", "strong", "sub", "sup", "time", "tt", "u", "var", "video",
        "wbr",
    )

    private const val BREAKS =
        "br, p, div, h1, h2, h3, h4, h5, h6, li, ul, ol, dl, dt, dd, blockquote, table, tr, td, th, section, " +
            "article, header, footer, aside, nav, figure, figcaption, pre, hr, main, center, address, details, " +
            "summary"

    private val MEDIA = setOf("img", "picture", "video", "audio", "svg", "canvas")
    private val MEDIA_SELECTOR = MEDIA.joinToString(", ")
}
