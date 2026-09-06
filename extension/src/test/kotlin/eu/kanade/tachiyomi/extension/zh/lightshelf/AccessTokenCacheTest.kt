package eu.kanade.tachiyomi.extension.zh.lightshelf

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.concurrent.TimeUnit

class AccessTokenCacheTest {
    @Test
    fun `opaque tokens reuse for the official thirty second cache window`() {
        var now = 0L
        val cache = AccessTokenCache({ now }, { 0 })
        cache.put("opaque-token")
        now = TimeUnit.SECONDS.toNanos(29)
        assertEquals("opaque-token", cache.get())
        now = TimeUnit.SECONDS.toNanos(30)
        assertNull(cache.get())
    }

    @Test
    fun `JWT cache refreshes before expiration using a monotonic clock`() {
        var monotonic = 0L
        var wall = 0L
        val cache = AccessTokenCache({ monotonic }, { wall })
        val token = "header." + Base64.getUrlEncoder().withoutPadding().encodeToString("{\"exp\":600}".toByteArray()) + ".signature"
        cache.put(token)
        wall = 999_000_000 // A later wall-clock adjustment must not extend or shorten the stored TTL.
        monotonic = TimeUnit.SECONDS.toNanos(569)
        assertEquals(token, cache.get())
        monotonic = TimeUnit.SECONDS.toNanos(570)
        assertNull(cache.get())
    }

    @Test
    fun `expired JWT is not given the opaque token fallback lifetime`() {
        val cache = AccessTokenCache({ 0 }, { 100_000 })
        val token = "h." + Base64.getUrlEncoder().encodeToString("{\"exp\":1}".toByteArray()) + ".s"
        cache.put(token)
        assertNull(cache.get())
    }

    @Test
    fun `explicit invalidation removes an otherwise valid token`() {
        val cache = AccessTokenCache()
        cache.put("opaque-token")
        cache.clear()
        assertNull(cache.get())
    }
}
