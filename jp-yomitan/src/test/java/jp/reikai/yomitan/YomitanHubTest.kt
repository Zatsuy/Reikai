package jp.reikai.yomitan

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class YomitanHubTest {

    private class FakePort : DocPort {
        var alive = true
        val received = mutableListOf<String>()
        override val binary = false
        override fun post(text: String): Boolean {
            if (alive) received += text
            return alive
        }

        override fun post(bytes: ByteArray) = alive

        fun headers(): List<JsonObject> = received.map { Json.parseToJsonElement(it.substringBefore('\n')).jsonObject }
        fun last(): JsonObject = headers().last()
        fun lastPayload(): String = received.last().substringAfter('\n')
    }

    private class FakeHost : HubHost {
        override val localStorage = YomitanStorage.InMemory()
        var backendReady = 0
        override fun openPage(how: String, url: String?): JsonElement? = null
        override fun fetch(request: FetchRequest, done: (FetchResult) -> Unit): () -> Unit = {}
        override fun onBackendReady() {
            backendReady++
        }

        override fun onTripwire(path: String, called: Boolean, url: String) {}
        override fun log(level: Char, text: String) {}
    }

    private val host = FakeHost()
    private val hub = YomitanHub(host)

    private inner class Doc(
        val view: Int,
        val origin: String = YomitanOrigin.ORIGIN,
        val mainFrame: Boolean = true,
        url: String,
    ) {
        val key = Any()
        val port = FakePort()

        init {
            say("""{"t":"hello","url":"$url"}""")
        }

        fun say(header: String, payload: String = "") =
            hub.onMessage(view, origin, mainFrame, key, port, if (payload.isEmpty()) header else "$header\n$payload")

        fun bye() = say("""{"t":"bye"}""")
    }

    private fun engineAndSettings(): Triple<Doc, Doc, Int> {
        val engineView = hub.registerView(PageKind.ENGINE)
        val settingsView = hub.registerView(PageKind.SETTINGS)
        val backend = Doc(engineView, url = YomitanOrigin.url("background.html"))
        val settings = Doc(settingsView, url = YomitanOrigin.url("settings.html"))
        return Triple(backend, settings, settingsView)
    }

    @Test
    fun `a message whose only receiver vanishes is answered with a closed port`() {
        val (backend, settings) = engineAndSettings()
        settings.say("""{"t":"send","mid":7}""", """{"action":"optionsGetFull"}""")
        backend.port.last()["t"]?.jsonPrimitive?.content shouldBe "msg"

        backend.bye()

        settings.port.last()["mid"]?.jsonPrimitive?.content shouldBe "7"
        settings.port.last()["err"]?.jsonPrimitive?.content shouldBe YomitanHub.PORT_CLOSED
    }

    @Test
    fun `a message still waits for the receivers that remain`() {
        val (backend, settings, _) = engineAndSettings()
        val search = Doc(hub.registerView(PageKind.SEARCH), url = YomitanOrigin.url("search.html"))
        backend.say("""{"t":"send","mid":3}""", """{"action":"applicationDatabaseUpdated"}""")
        val rid = search.port.last()["rid"]!!.jsonPrimitive.content

        settings.bye()
        search.say("""{"t":"resp","rid":$rid,"has":true}""", "42")

        backend.port.last()["err"] shouldBe null
        backend.port.lastPayload() shouldBe "42"
    }

    @Test
    fun `a port loses its other end when a document vanishes`() {
        val (backend, settings, settingsView) = engineAndSettings()
        backend.say("""{"t":"connect","pid":"p1","tabId":$settingsView,"name":"frame"}""")
        settings.port.last()["t"]?.jsonPrimitive?.content shouldBe "onconnect"

        settings.bye()

        backend.port.last()["t"]?.jsonPrimitive?.content shouldBe "pdisc"
        backend.port.last()["pid"]?.jsonPrimitive?.content shouldBe "p1"
    }

    @Test
    fun `a native call fails when the backend vanishes before answering`() {
        val (backend) = engineAndSettings()
        var outcome: Result<String>? = null
        hub.callBackend("findTerms", """{"text":"猫"}""") { outcome = it }

        backend.bye()

        outcome?.exceptionOrNull()?.message shouldBe "The engine stopped before it answered"
    }

    @Test
    fun `a document that can no longer be reached is forgotten`() {
        val (backend, settings) = engineAndSettings()
        backend.port.alive = false

        settings.say("""{"t":"send","mid":1}""", """{"action":"optionsGetFull"}""")

        settings.port.last()["err"]?.jsonPrimitive?.content shouldBe YomitanHub.PORT_CLOSED
        hub.hasBackend shouldBe false
    }

    @Test
    fun `a navigation keeps the new page that already said hello`() {
        val (_, settings, settingsView) = engineAndSettings()
        val next = Doc(settingsView, url = YomitanOrigin.url("info.html"))

        hub.forgetView(settingsView, keepUrl = YomitanOrigin.url("info.html"))
        next.say("""{"t":"req","op":"tabs","mid":1}""")

        next.port.lastPayload() shouldBe """[{"id":$settingsView,"url":"${YomitanOrigin.url("info.html")}"}]"""
        settings.port.received.size shouldBe 1
    }

    @Test
    fun `the backend's ready announcement marks the engine ready`() {
        val (backend) = engineAndSettings()

        backend.say(
            """{"t":"send","mid":1,"action":"applicationBackendReady"}""",
            """{"action":"applicationBackendReady"}""",
        )

        host.backendReady shouldBe 1
    }

    @Test
    fun `a page cannot announce the backend ready`() {
        val (_, settings) = engineAndSettings()

        settings.say(
            """{"t":"send","mid":1,"action":"applicationBackendReady"}""",
            """{"action":"applicationBackendReady"}""",
        )

        host.backendReady shouldBe 0
    }

    @ParameterizedTest(name = "{0} {1}: storage allowed {2}")
    @CsvSource(
        "ENGINE, true, true",
        "SETTINGS, true, true",
        "SEARCH, true, true",
        "POPUP, true, false",
        "SEARCH, false, false",
        "READER, true, false",
    )
    fun `only the backend and the settings and search pages reach storage`(
        kind: PageKind,
        mainFrame: Boolean,
        allowed: Boolean,
    ) {
        val doc = Doc(hub.registerView(kind), mainFrame = mainFrame, url = YomitanOrigin.url("x.html"))

        doc.say("""{"t":"req","op":"sset","area":"local","mid":1}""", """{"options":"{}"}""")

        (doc.port.last()["err"] == null) shouldBe allowed
        (host.localStorage.get(null).isNotEmpty()) shouldBe allowed
    }

    @ParameterizedTest(name = "{0} over the network: allowed {1}")
    @CsvSource("SETTINGS, true", "POPUP, false")
    fun `only trusted pages may fetch`(kind: PageKind, allowed: Boolean) {
        val doc = Doc(hub.registerView(kind), url = YomitanOrigin.url("x.html"))

        doc.say(
            """{"t":"req","op":"fetch","mid":1}""",
            """{"url":"https://example.com/","method":"GET","headers":{}}""",
        )

        (doc.port.received.none { "err" in Json.parseToJsonElement(it.substringBefore('\n')).jsonObject }) shouldBe
            allowed
    }

    @ParameterizedTest(name = "a chapter page sending {0}: delivered {1}")
    @CsvSource(
        "termsFind, true",
        "optionsGet, true",
        "modifySettings, false",
        "addAnkiNote, false",
        "optionsGetFull, false",
    )
    fun `a chapter page may send only content-script actions`(action: String, delivered: Boolean) {
        val (backend) = engineAndSettings()
        val chapter =
            Doc(hub.registerView(PageKind.READER), origin = "https://kakuyomu.jp", url = "https://kakuyomu.jp/works/1")

        chapter.say("""{"t":"send","mid":5}""", """{"action":"$action"}""")

        (backend.port.last()["t"]?.jsonPrimitive?.content == "msg") shouldBe delivered
    }

    @Test
    fun `a chapter page may not message another tab`() {
        val (_, _, settingsView) = engineAndSettings()
        val chapter =
            Doc(hub.registerView(PageKind.READER), origin = "https://kakuyomu.jp", url = "https://kakuyomu.jp/works/1")

        chapter.say("""{"t":"tabsend","mid":2,"tabId":$settingsView}""", """{"action":"x"}""")

        chapter.port.last()["err"]?.jsonPrimitive?.content shouldBe
            "Reikai JP does not let a web page message another tab"
    }

    @Test
    fun `an engine-origin frame in a chapter's WebView is an extension frame of that tab`() {
        val readerView = hub.registerView(PageKind.READER)
        val chapter = Doc(readerView, origin = "https://kakuyomu.jp", url = "https://kakuyomu.jp/works/1")
        val popup = Doc(readerView, mainFrame = false, url = YomitanOrigin.url("popup.html"))

        chapter.say("""{"t":"tabsend","mid":2,"tabId":$readerView,"frameId":1}""", """{"action":"x"}""")

        popup.port.headers().map { it["t"]!!.jsonPrimitive.content } shouldContainExactly listOf("welcome", "msg")
        popup.port.headers().first()["role"]?.jsonPrimitive?.content shouldBe "frame"
    }

    @Test
    fun `session storage is kept by the hub across documents`() {
        val (backend, settings) = engineAndSettings()
        backend.say("""{"t":"req","op":"sset","area":"session","mid":1}""", """{"openedWelcomePage":"true"}""")

        settings.say("""{"t":"req","op":"sget","area":"session","mid":2}""", """["openedWelcomePage"]""")

        settings.port.lastPayload() shouldBe """{"openedWelcomePage":"true"}"""
        host.localStorage.get(null).keys.shouldBeEmpty()
    }
}
