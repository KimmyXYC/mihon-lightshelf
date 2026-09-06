package eu.kanade.tachiyomi.extension.zh.lightshelf

import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Memory only. exp is a scheduling hint, never a replacement for server authentication. */
internal class AccessTokenCache(
    private val monotonicNanos: () -> Long = System::nanoTime,
    private val wallMillis: () -> Long = System::currentTimeMillis,
) {
    private var token: String? = null
    private var validUntil = 0L

    fun get(): String? = token?.takeIf { monotonicNanos() < validUntil }

    fun put(value: String): String {
        val ttl = runCatching {
            val payload = value.split('.').takeIf { it.size == 3 }?.get(1) ?: return@runCatching null
            val claims = JSONObject(String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8))
            if (!claims.has("exp")) return@runCatching null
            val remaining = (claims.getLong("exp") * 1000 - wallMillis()).coerceAtLeast(0)
            remaining - minOf(30_000, remaining / 10)
        }.getOrNull() ?: 30_000L // Official Web config caches opaque tokens for 30 seconds.
        token = value
        validUntil = monotonicNanos() + TimeUnit.MILLISECONDS.toNanos(ttl.coerceAtMost(TimeUnit.DAYS.toMillis(1)))
        return value
    }

    fun clear() { token = null; validUntil = 0 }
}
