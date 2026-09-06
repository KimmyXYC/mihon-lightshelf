package eu.kanade.tachiyomi.extension.zh.lightshelf

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LightShelfApiTest {
    private class Store : SessionStore {
        override var refreshToken = ""
        override var tokenOnly = false
        override var email = "test@example.org"
        override val passwordHash = LightShelfApi.passwordHash("test-only-password")
        override val visitorId = "test-visitor"
    }

    private class Fixture(idleMillis: Long = 30_000) : Closeable {
        val store = Store()
        val client = OkHttpClient()
        val server = MockWebServer()
        val logins = AtomicInteger()
        val refreshes = AtomicInteger()
        val negotiations = AtomicInteger()
        val invocations = AtomicInteger()
        val rejectedInvocations = AtomicInteger()
        val gatewayInvocations = AtomicInteger()
        val deletes = LinkedBlockingQueue<String>()
        val refreshCodes = ConcurrentLinkedQueue<Int>()
        val loginCodes = ConcurrentLinkedQueue<Int>()
        val invocationIds = ConcurrentLinkedQueue<String>()
        val authorizations = ConcurrentLinkedQueue<String>()
        val noHandshake = AtomicBoolean(false)
        val sessions = ConcurrentHashMap<String, LinkedBlockingQueue<String>>()
        val initialPolls = ConcurrentHashMap.newKeySet<String>()
        var clock = 0L
        val tokens = AccessTokenCache({ clock }, { 0L })
        val api: LightShelfApi

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    if (path == "/api/user/login") {
                        logins.incrementAndGet()
                        val code = loginCodes.poll() ?: 200
                        if (code != 200) return MockResponse().setResponseCode(code)
                        val body = JSONObject(request.body.readUtf8())
                        check(body.getString("email") == store.email && body.getString("password") == store.passwordHash)
                        return response(json("Token" to "login-access", "RefreshToken" to "refresh-one"))
                    }
                    if (path == "/api/user/refresh_token") {
                        val n = refreshes.incrementAndGet()
                        val code = refreshCodes.poll() ?: 200
                        return if (code == 200) response("refresh-access-$n") else MockResponse().setResponseCode(code)
                    }
                    authorizations.add(request.getHeader("Authorization").orEmpty())
                    if (path.endsWith("/negotiate")) {
                        val id = "secret-${negotiations.incrementAndGet()}"
                        sessions[id] = LinkedBlockingQueue()
                        return MockResponse().setBody("""{"connectionId":"public-id","connectionToken":"$id","availableTransports":[{"transport":"LongPolling","transferFormats":["Text"]}]}""")
                    }
                    val id = request.requestUrl!!.queryParameter("id") ?: return MockResponse().setResponseCode(400)
                    val queue = sessions[id] ?: return MockResponse().setResponseCode(404)
                    if (request.method == "DELETE") {
                        deletes.offer(id)
                        queue.offer("CLOSED")
                        return MockResponse().setResponseCode(202)
                    }
                    if (request.method == "GET") {
                        if (initialPolls.add(id)) return MockResponse().setBody("")
                        val message = queue.poll(2, TimeUnit.SECONDS).orEmpty()
                        return if (message == "CLOSED") MockResponse().setResponseCode(204) else MockResponse().setBody(message)
                    }
                    val message = JSONObject(request.body.readUtf8().trimEnd('\u001e'))
                    if (message.has("protocol")) {
                        if (!noHandshake.get()) queue.offer("{}\u001e{\"type\":1,\"target\":\"OnMessage\",\"arguments\":[]}\u001e{\"type\":6}\u001e")
                    } else if (message.optInt("type") == 1) {
                        invocations.incrementAndGet()
                        val invocationId = message.getString("invocationId")
                        invocationIds.add(invocationId)
                        check(!message.getJSONArray("arguments").getJSONObject(1).getBoolean("UseGzip"))
                        if (gatewayInvocations.getAndUpdate { maxOf(0, it - 1) } > 0) return MockResponse().setResponseCode(502)
                        val completion = if (rejectedInvocations.getAndUpdate { maxOf(0, it - 1) } > 0)
                            json("type" to 3, "invocationId" to invocationId, "error" to "Failed because user is unauthorized")
                        else json("type" to 3, "invocationId" to invocationId, "result" to json("success" to true, "response" to json("totalPages" to 2)))
                        queue.offer(completion.toString() + '\u001e')
                    }
                    return MockResponse().setResponseCode(202)
                }
            }
            api = LightShelfApi(client, store, server.url("/").toString().trimEnd('/'), 1, tokens, idleMillis) {}
        }

        fun call() = api.call("GetComicList", json("Page" to 1))
        private fun response(value: Any) = MockResponse().setBody(json("success" to true, "response" to value).toString())
        override fun close() {
            api.close()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            while (deletes.size < negotiations.get() && System.nanoTime() < deadline) Thread.sleep(10)
            sessions.values.forEach { it.offer("CLOSED") }
            client.connectionPool.evictAll()
            server.close()
            client.dispatcher.executorService.shutdownNow()
        }
    }

    @Test
    fun `sequential requests reuse token and hub with unique invocation ids`() = Fixture().use { f ->
        repeat(3) { assertEquals(2, f.call().number("TotalPages")) }
        assertEquals(1, f.logins.get())
        assertEquals(0, f.refreshes.get())
        assertEquals(1, f.negotiations.get())
        assertEquals(listOf("1", "2", "3"), f.invocationIds.toList())
        assertTrue(f.authorizations.all { it == "Bearer login-access" })
    }

    @Test
    fun `concurrent requests perform one login and share a session`() = Fixture().use { f ->
        val pool = Executors.newFixedThreadPool(6)
        try {
            val tasks = (0 until 6).map { pool.submit<Int> { f.call().number("TotalPages") } }
            tasks.forEach { assertEquals(2, it.get(5, TimeUnit.SECONDS)) }
            assertEquals(1, f.logins.get())
            assertEquals(1, f.negotiations.get())
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `expiry refreshes once and replaces the session without logging in again`() = Fixture().use { f ->
        f.call()
        f.clock = TimeUnit.SECONDS.toNanos(31)
        f.call()
        f.call()
        assertEquals(1, f.logins.get())
        assertEquals(1, f.refreshes.get())
        assertEquals(2, f.negotiations.get())
    }

    @Test
    fun `explicit authentication rejection refreshes and retries once`() = Fixture().use { f ->
        f.call()
        f.rejectedInvocations.set(1)
        f.call()
        assertEquals(1, f.logins.get())
        assertEquals(1, f.refreshes.get())
        assertEquals(2, f.negotiations.get())
        assertEquals(3, f.invocations.get())
    }

    @Test
    fun `repeated authentication rejection stops after one recovery attempt`() = Fixture().use { f ->
        f.rejectedInvocations.set(5)
        assertThrows(ApiException::class.java) { f.call() }
        assertEquals(2, f.invocations.get())
        assertEquals(1, f.refreshes.get())
        assertEquals("refresh-one", f.store.refreshToken)
    }

    @Test
    fun `transient refresh 502 retries without discarding refresh token`() = Fixture().use { f ->
        f.store.refreshToken = "existing-refresh"
        f.refreshCodes.add(502)
        assertEquals(2, f.call().number("TotalPages"))
        assertEquals(2, f.refreshes.get())
        assertEquals(0, f.logins.get())
        assertEquals("existing-refresh", f.store.refreshToken)
    }

    @Test
    fun `persistent gateway failure stops after two attempts and preserves credentials`() = Fixture().use { f ->
        f.store.refreshToken = "existing-refresh"
        f.refreshCodes.addAll(listOf(502, 502, 502))
        val e = assertThrows(ApiException::class.java) { f.call() }
        assertEquals(502, e.status)
        assertTrue(e.message!!.contains("令牌续期"))
        assertEquals(2, f.refreshes.get())
        assertEquals(0, f.logins.get())
        assertEquals("existing-refresh", f.store.refreshToken)
    }

    @Test
    fun `HTTP 403 and 404 do not turn routing failures into login attempts`() {
        for (code in listOf(403, 404)) Fixture().use { f ->
            f.store.refreshToken = "existing-refresh"
            f.refreshCodes.add(code)
            assertThrows(ApiException::class.java) { f.call() }
            assertEquals(1, f.refreshes.get())
            assertEquals(0, f.logins.get())
            assertEquals("existing-refresh", f.store.refreshToken)
        }
    }

    @Test
    fun `revoked refresh credential triggers account login`() = Fixture().use { f ->
        f.store.refreshToken = "revoked-refresh"
        f.refreshCodes.add(401)
        f.call()
        assertEquals(1, f.logins.get())
        assertEquals(1, f.refreshes.get())
        assertEquals("refresh-one", f.store.refreshToken)
    }

    @Test
    fun `login 502 has a bounded retry`() = Fixture().use { f ->
        f.loginCodes.add(502)
        f.call()
        assertEquals(2, f.logins.get())
        assertEquals(0, f.refreshes.get())
    }

    @Test
    fun `hub gateway failure reconnects while reusing the valid access token`() = Fixture().use { f ->
        f.gatewayInvocations.set(1)
        f.call()
        assertEquals(1, f.logins.get())
        assertEquals(0, f.refreshes.get())
        assertEquals(2, f.negotiations.get())
    }

    @Test
    fun `idle session closes and the next call still reuses its valid token`() = Fixture(idleMillis = 100).use { f ->
        f.call()
        assertNotNull(f.deletes.poll(3, TimeUnit.SECONDS))
        f.call()
        assertEquals(1, f.logins.get())
        assertEquals(0, f.refreshes.get())
        assertEquals(2, f.negotiations.get())
        // Put back consumed cleanup event for fixture shutdown accounting.
        f.deletes.add("consumed")
        Unit
    }

    @Test
    fun `closing on credential change invalidates cached auth and connection`() = Fixture().use { f ->
        f.call()
        f.api.close()
        f.store.refreshToken = ""
        f.call()
        assertEquals(2, f.logins.get())
        assertEquals(2, f.negotiations.get())
    }

    @Test
    fun `handshake timeout is bounded and cancels polling`() = Fixture().use { f ->
        f.noHandshake.set(true)
        assertThrows(TransportException::class.java) { f.call() }
        assertEquals(2, f.negotiations.get())
        assertEquals(1, f.logins.get())
        assertNotNull(f.deletes.poll(3, TimeUnit.SECONDS))
        f.deletes.add("consumed")
        Unit
    }

    @Test
    fun `frames may span responses or share one response`() {
        val frames = HubFrames()
        assertTrue(frames.append("{\"type\":").isEmpty())
        assertEquals(2, frames.append("6}\u001e{}\u001e{\"type\":3}").size)
        assertEquals(3, frames.append("\u001e").single().getInt("type"))
    }

    @Test
    fun `manual refresh token works without account credentials`() = Fixture().use { f ->
        f.store.email = ""
        f.store.refreshToken = "manual-refresh"
        f.store.tokenOnly = true
        f.call()
        assertEquals(0, f.logins.get())
        assertEquals(1, f.refreshes.get())
    }

    @Test
    fun `invalid manual token does not silently switch back to saved account`() = Fixture().use { f ->
        f.store.refreshToken = "manual-refresh"
        f.store.tokenOnly = true
        f.refreshCodes.add(401)
        val e = assertThrows(ApiException::class.java) { f.call() }
        assertTrue(e.message!!.contains("自定义 Token"))
        assertEquals(0, f.logins.get())
        assertEquals("manual-refresh", f.store.refreshToken)
    }

    @Test
    fun `changing line closes old session and refreshes on selected server`() = Fixture().use { first ->
        Fixture().use { second ->
            first.call()
            first.api.setEndpoint(second.server.url("/").toString().trimEnd('/'))
            first.call()
            assertEquals(1, first.negotiations.get())
            assertEquals(1, second.negotiations.get())
            assertEquals(1, second.refreshes.get())
            assertEquals(0, second.logins.get())
            assertEquals("refresh-one", first.store.refreshToken)
            first.api.close()
        }
    }

    @Test
    fun `password hashing follows lowercase SHA256 convention`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", LightShelfApi.passwordHash("abc"))
    }

    @Test
    fun `server envelope errors retain status`() {
        val failure = assertThrows(ApiException::class.java) {
            LightShelfApi.unwrap(JSONObject("""{"success":false,"status":401,"msg":"请登录"}"""))
        }
        assertEquals(401, failure.status)
        assertTrue(failure.envelope)
    }

    @Test
    fun `transport never depends on host WebSocket listener ABI`() {
        val bytes = HubSession::class.java.getResourceAsStream("HubSession.class")!!.use { it.readBytes() }.toString(Charsets.ISO_8859_1)
        assertFalse(bytes.contains("okhttp3/WebSocket"))
        assertFalse(bytes.contains("newWebSocket"))
    }
}
