package eu.kanade.tachiyomi.extension.zh.lightshelf

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ComicCatalogTest {
    @Test
    fun `series follows all pages and maps current book info without old hub methods`() {
        val calls = mutableListOf<String>()
        val catalog = ComicCatalog { method, params ->
            calls.add(method)
            when (method) {
                "GetBooksBySeries" -> {
                    assertEquals("Comic", params.string("Type"))
                    assertEquals("系列名", params.string("SeriesName"))
                    json("Data" to JSONArray().put(json("Id" to params.number("Page"))), "TotalPages" to 2)
                }
                "GetBookInfo" -> {
                    val id = params.number("Id")
                    json("Book" to json("Id" to id, "Type" to "Comic", "Title" to "版本 $id",
                        "User" to json("UserName" to "上传者 $id"),
                        "Chapters" to JSONArray().put(json("Id" to id * 10, "SortNum" to 1, "Title" to "第一话"))))
                }
                else -> error("Unexpected method $method")
            }
        }
        val result = catalog.series("系列名")
        assertEquals("系列名", result.obj("Series").string("Title"))
        assertEquals(listOf(20, 10), ComicChapters.parse(result).map { it.id })
        assertEquals("上传者 2 · 版本 2", ComicChapters.parse(result).first().uploader)
        assertEquals(listOf("GetBooksBySeries", "GetBooksBySeries", "GetBookInfo", "GetBookInfo"), calls)
    }

    @Test
    fun `novel result is rejected even if returned by comic filtered list`() {
        val catalog = ComicCatalog { method, _ ->
            if (method == "GetBooksBySeries") json("Data" to JSONArray().put(json("Id" to 1)), "TotalPages" to 1)
            else json("Book" to json("Type" to "Novel"))
        }
        assertTrue(assertThrows(IOException::class.java) { catalog.series("漫画") }.message!!.contains("不是漫画"))
    }

    @Test
    fun `repeated pages stop instead of looping forever`() {
        var count = 0
        val catalog = ComicCatalog { _, _ ->
            count++
            json("Data" to JSONArray().put(json("Id" to 1)), "TotalPages" to 99)
        }
        assertThrows(IOException::class.java) { catalog.series("漫画") }
        assertEquals(2, count)
    }
}
