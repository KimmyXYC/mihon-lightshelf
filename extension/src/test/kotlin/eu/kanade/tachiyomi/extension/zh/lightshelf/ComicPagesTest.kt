package eu.kanade.tachiyomi.extension.zh.lightshelf

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ComicPagesTest {
    @Test
    fun `a 166 page volume resolves every page in 28 batches without truncation`() {
        val requests = mutableListOf<Int>()
        val pages = ComicPages { chapter, skip ->
            requests += skip
            json("chapter" to json(
                "id" to chapter, "total" to 166, "skip" to skip,
                "images" to JSONArray((skip until minOf(skip + 6, 166)).map { "https://example.org/$it.jpg" }),
            ))
        }
        assertEquals(166, pages.batch(123, 0).total)
        assertEquals(listOf(0), requests)
        for (index in 0 until 166) assertEquals("https://example.org/$index.jpg", pages.image(123, index))
        assertEquals((0 until 166 step 6).toList(), requests)
    }

    @Test
    fun `cache separates chapters and clearing it forces a new request`() {
        var calls = 0
        val pages = ComicPages { chapter, skip ->
            calls++
            json("Chapter" to json(
                "Id" to chapter, "Total" to 2, "Skip" to skip,
                "Images" to JSONArray(listOf("https://example.org/$chapter/0", "https://example.org/$chapter/1")),
            ))
        }
        assertEquals("https://example.org/1/0", pages.image(1, 0))
        assertEquals("https://example.org/1/1", pages.image(1, 1))
        assertEquals(1, calls)
        assertEquals("https://example.org/2/0", pages.image(2, 0))
        pages.clear()
        pages.image(1, 0)
        assertEquals(3, calls)
    }

    @Test(expected = IOException::class)
    fun `empty partial batch fails instead of silently dropping pages`() {
        ComicBatch.parse(json("chapter" to json("id" to 1, "total" to 20, "skip" to 6, "images" to JSONArray())), 1, 6)
    }

    @Test(expected = IOException::class)
    fun `unexpected offset fails instead of shifting page numbers`() {
        ComicBatch.parse(json("chapter" to json(
            "id" to 1, "total" to 20, "skip" to 0, "images" to JSONArray(listOf("https://example.org/0")),
        )), 1, 6)
    }
}
