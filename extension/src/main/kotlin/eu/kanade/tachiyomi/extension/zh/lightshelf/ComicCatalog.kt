package eu.kanade.tachiyomi.extension.zh.lightshelf

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

internal class ComicCatalog(private val call: (String, JSONObject) -> JSONObject) {
    /** Preserve existing series URLs while the official site now uses numeric book routes. */
    fun series(title: String): JSONObject {
        val ids = linkedSetOf<Int>()
        var page = 1
        do {
            val result = call("GetBooksBySeries", json(
                "Type" to "Comic", "SeriesName" to title, "Order" to "new", "Page" to page, "Size" to 24,
            ))
            val items = result.array("Data").objects()
            if (items.isEmpty()) break
            val previous = ids.size
            items.forEach { ids.add(it.number("Id")) }
            if (ids.size == previous) throw IOException("漫画系列分页未前进，请稍后重试。")
            val more = page < result.number("TotalPages")
            page++
        } while (more)
        if (ids.isEmpty()) throw IOException("未找到此漫画系列，请重新搜索。")
        val books = ids.map { id ->
            val book = call("GetBookInfo", json("Id" to id)).obj("Book")
            if (book.optionalString("Type") != "Comic") throw IOException("此条目不是漫画。")
            book.put("Uploader", book.obj("User"))
        }
        val info = JSONObject(books.first().toString()).put("Title", title)
        return json("Series" to info, "Books" to JSONArray(books))
    }
}
