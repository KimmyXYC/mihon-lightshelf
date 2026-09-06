package eu.kanade.tachiyomi.extension.zh.lightshelf

import org.json.JSONObject

internal data class ComicChapter(
    val id: Int,
    val bookId: Int,
    val title: String,
    val number: Float,
    val uploader: String,
    val createdAt: String,
)

internal object ComicChapters {
    /** Keep upload editions together, ordering each edition by SortNum, newest chapter first. */
    fun parse(response: JSONObject): List<ComicChapter> = response.array("Books").objects()
        .sortedWith(compareBy<JSONObject> { it.optionalString("CreatedAt") }.thenBy { it.number("Id") })
        .flatMap { book ->
            book.array("Chapters").objects()
                .sortedWith(compareBy<JSONObject> { (it.value("SortNum") as Number).toDouble() }.thenBy { it.number("Id") })
                .map { chapter ->
                    ComicChapter(
                        id = chapter.number("Id"),
                        bookId = book.number("Id"),
                        title = chapter.string("Title"),
                        number = (chapter.value("SortNum") as Number).toFloat(),
                        uploader = "${book.obj("Uploader").optionalString("UserName")} · ${book.string("Title")}",
                        createdAt = chapter.optionalString("UpdatedAt").ifBlank { chapter.optionalString("CreatedAt") },
                    )
                }
        }.distinctBy { it.id }.reversed()
}
