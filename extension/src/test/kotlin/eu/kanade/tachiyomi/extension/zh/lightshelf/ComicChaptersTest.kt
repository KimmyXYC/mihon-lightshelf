package eu.kanade.tachiyomi.extension.zh.lightshelf

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ComicChaptersTest {
    @Test
    fun `editions remain separate and chapter sort numbers override response order`() {
        val result = ComicChapters.parse(JSONObject("""
            {"books":[
              {"id":2,"title":"Edition B","createdAt":"2026-08-02T00:00:00Z","uploader":{"userName":"B"},"chapters":[
                {"id":21,"title":"Vol 1","sortNum":1,"createdAt":"2026-08-02T00:00:00Z"}
              ]},
              {"id":1,"title":"Edition A","createdAt":"2026-08-01T00:00:00Z","uploader":{"userName":"A"},"chapters":[
                {"id":12,"title":"Vol 2","sortNum":2,"createdAt":"2026-08-01T00:00:00Z","updatedAt":"2026-08-03T00:00:00Z"},
                {"id":11,"title":"Vol 1","sortNum":1,"createdAt":"2026-08-01T00:00:00Z","updatedAt":null}
              ]}
            ]}
        """))
        assertEquals(listOf(21, 12, 11), result.map { it.id })
        assertEquals(listOf(2, 1, 1), result.map { it.bookId })
        assertEquals("B · Edition B", result.first().uploader)
        assertEquals("2026-08-03T00:00:00Z", result[1].createdAt)
        assertEquals("2026-08-01T00:00:00Z", result[2].createdAt)
    }
}
