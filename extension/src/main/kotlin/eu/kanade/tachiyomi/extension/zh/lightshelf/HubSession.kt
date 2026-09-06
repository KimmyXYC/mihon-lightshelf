package eu.kanade.tachiyomi.extension.zh.lightshelf

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** HTTP-only: never inherit host WebSocketListener methods that R8 may have made final. */
internal class HubSession(
    private val client: OkHttpClient,
    private val request: Request,
    val token: String,
    private val timeoutSeconds: Long,
    private val idleMillis: Long,
) : Closeable {
    private val frames = HubFrames()
    private val messages = LinkedBlockingQueue<Any>(128)
    private val activePoll = AtomicReference<Call?>()
    private val lifecycle = Any()
    private val sendLock = Any()
    private var idleTask: ScheduledFuture<*>? = null
    private var heartbeat: ScheduledFuture<*>? = null
    private var idleGeneration = 0L
    @Volatile private var closed = false
    @Volatile private var failure: IOException? = null
    @Volatile private var connection: Request? = null
    private var invocation = 0
    val isOpen: Boolean get() = !closed && failure == null

    private fun send(text: String, seconds: Long = timeoutSeconds) = synchronized(sendLock) {
        failure?.let { throw it }
        if (closed) throw TransportException("轻书架会话已关闭，请重试。")
        hubHttp(client, connection!!.newBuilder().post(text.toRequestBody(TEXT)).build(), seconds, "发送漫画请求")
    }

    private fun receive(deadline: Long): JSONObject {
        failure?.let { throw it }
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) throw TransportException("轻书架请求超时。")
        return when (val message = messages.poll(remaining, TimeUnit.NANOSECONDS)) {
            is JSONObject -> message
            is IOException -> throw message
            else -> throw TransportException("轻书架请求超时。")
        }
    }

    private fun fail(error: IOException) {
        failure = error
        messages.offer(error)
        // Close transport resources immediately, including failures while no caller is waiting.
        close()
    }

    private fun start(deadline: Long) {
        val negotiate = JSONObject(hubHttp(client, request.newBuilder()
            .url(request.url.newBuilder().addPathSegment("negotiate").addQueryParameter("negotiateVersion", "1").build())
            .post("".toRequestBody(TEXT)).build(), timeoutSeconds, "会话协商"))
        val supported = negotiate.optJSONArray("availableTransports")?.objects()?.any {
            it.optString("transport") == "LongPolling" &&
                it.optJSONArray("transferFormats")?.let { formats ->
                    (0 until formats.length()).any { i -> formats.optString(i) == "Text" }
                } == true
        } == true
        if (!supported) throw IOException("轻书架未提供兼容的 SignalR Long Polling 传输。")
        val id = negotiate.optString("connectionToken")
        if (id.isBlank()) throw IOException("轻书架未返回连接凭据。")
        connection = request.newBuilder().url(request.url.newBuilder().addQueryParameter("id", id).build())
            .header("Cache-Control", "no-cache").build()
        accept(hubHttp(client, connection!!, timeoutSeconds, "建立会话"))
        Thread({
            try {
                while (!closed) {
                    val text = hubHttp(client, connection!!, 95, "接收漫画数据", activePoll) { closed }
                    if (!closed) accept(text)
                }
            } catch (e: IOException) {
                if (!closed) fail(e)
            } catch (_: Exception) {
                if (!closed) fail(IOException("轻书架返回了无效的 SignalR 数据。"))
            }
        }, "LightShelf-poll").apply { isDaemon = true }.start()
        send("{\"protocol\":\"json\",\"version\":1}\u001e")
        val handshake = receive(deadline)
        if (handshake.has("error") || handshake.has("type")) throw IOException("轻书架不接受 SignalR JSON 握手。")
        synchronized(lifecycle) {
            if (!closed) heartbeat = scheduler.scheduleWithFixedDelay({
                try { send("{\"type\":6}\u001e", 3) } catch (e: IOException) { if (!closed) fail(e) }
            }, 15, 15, TimeUnit.SECONDS)
        }
    }

    private fun accept(text: String) {
        for (frame in frames.append(text)) {
            if (frame.optInt("type") == 1 || frame.optInt("type") == 6) continue // Broadcasts/pings carry no result.
            if (frame.optInt("type") == 7) throw TransportException("轻书架关闭了会话，请重试。")
            if (!messages.offer(frame)) throw IOException("轻书架返回了过多未处理消息。")
        }
    }

    fun invoke(method: String, params: JSONObject): JSONObject {
        synchronized(lifecycle) {
            failure?.let { throw it }
            if (closed) throw TransportException("轻书架会话已关闭，请重试。")
            idleGeneration++
            idleTask?.cancel(false)
            idleTask = null
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        try {
            if (connection == null) start(deadline)
            val id = (++invocation).toString()
            send(json("type" to 1, "invocationId" to id, "target" to method,
                "arguments" to JSONArray().put(params).put(json("UseGzip" to false))).toString() + '\u001e')
            while (true) {
                val message = receive(deadline)
                if (message.optInt("type") != 3 || message.optString("invocationId") != id) continue
                if (message.has("error")) {
                    val error = message.optString("error")
                    val auth = error.contains("unauthorized", true) || error.contains("unauthorised", true) || error.contains("invalid token", true)
                    throw ApiException(if (auth) "轻书架登录状态已过期。" else "轻书架无法完成漫画请求。", if (auth) 401 else 0)
                }
                return LightShelfApi.unwrap(message.getJSONObject("result")) as JSONObject
            }
        } finally {
            synchronized(lifecycle) {
                if (!closed) {
                    val generation = ++idleGeneration
                    idleTask = scheduler.schedule({
                        synchronized(lifecycle) { if (generation == idleGeneration) close() }
                    }, idleMillis, TimeUnit.MILLISECONDS)
                }
            }
        }
    }

    override fun close() {
        val active: Request?
        synchronized(lifecycle) {
            if (closed) return
            closed = true
            idleTask?.cancel(false)
            heartbeat?.cancel(false)
            activePoll.getAndSet(null)?.cancel()
            active = connection
        }
        messages.offer(TransportException("轻书架会话已关闭。"))
        if (active != null) {
            // Credential preferences call close() on the Android UI thread: DELETE must be asynchronous.
            scheduler.execute {
                try { hubHttp(client, active.newBuilder().method("DELETE", null).build(), 3, "关闭会话") }
                catch (_: IOException) { /* Server also expires abandoned connections. */ }
            }
        }
    }

    companion object {
        private val TEXT = "text/plain;charset=UTF-8".toMediaType()
        private val scheduler = ScheduledThreadPoolExecutor(2) { task ->
            Thread(task, "LightShelf-session-timer").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true; setKeepAliveTime(10, TimeUnit.SECONDS); allowCoreThreadTimeOut(true) }
    }
}

internal fun hubHttp(
    client: OkHttpClient,
    request: Request,
    timeoutSeconds: Long,
    stage: String,
    active: AtomicReference<Call?>? = null,
    cancelled: () -> Boolean = { false },
): String {
    val call = client.newCall(request)
    call.timeout().timeout(timeoutSeconds, TimeUnit.SECONDS)
    active?.set(call)
    if (cancelled()) call.cancel()
    try {
        return call.execute().use { response ->
            if (response.code == 204) throw ApiException("轻书架会话已关闭。", 204)
            if (!response.isSuccessful) {
                val message = if (response.code in listOf(502, 503, 504))
                    "轻书架服务器暂不可用（$stage，HTTP ${response.code}），请稍后重试。"
                else "轻书架请求失败（$stage，HTTP ${response.code}）。"
                throw ApiException(message, response.code)
            }
            response.body.string()
        }
    } catch (e: ApiException) { throw e }
    catch (_: IOException) { throw TransportException("轻书架网络中断或超时（$stage），请重试。") }
    finally { active?.compareAndSet(call, null) }
}
/** Buffers record separators independently of HTTP response boundaries. */
internal class HubFrames {
    private val pending = StringBuilder()

    fun append(text: String): List<JSONObject> {
        pending.append(text)
        if (pending.length > 8 * 1024 * 1024) throw IOException("轻书架响应过大。")
        val frames = mutableListOf<JSONObject>()
        var end = pending.indexOf("\u001e")
        while (end >= 0) {
            val frame = pending.substring(0, end)
            pending.delete(0, end + 1)
            if (frame.isNotBlank()) frames += JSONObject(frame)
            end = pending.indexOf("\u001e")
        }
        return frames
    }
}
