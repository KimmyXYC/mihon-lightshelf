package eu.kanade.tachiyomi.extension.zh.lightshelf

import org.json.JSONObject
import java.io.IOException

internal data class ComicBatch(val chapterId: Int, val total: Int, val skip: Int, val images: List<String>) {
    companion object {
        fun parse(response: JSONObject, expectedChapter: Int, expectedSkip: Int): ComicBatch {
            val chapter = response.obj("Chapter")
            val urls = chapter.array("Images")
            val batch = ComicBatch(
                chapter.number("Id"), chapter.number("Total"), chapter.number("Skip"),
                (0 until urls.length()).map { urls.getString(it) },
            )
            if (batch.chapterId != expectedChapter || batch.skip != expectedSkip || batch.total < 0 ||
                batch.skip + batch.images.size > batch.total ||
                (batch.skip < batch.total && batch.images.isEmpty()) ||
                batch.images.any { !it.startsWith("https://") && !it.startsWith("http://") }
            ) throw IOException("轻书架图片分页数据不完整，请重试。")
            return batch
        }
    }
}

/** Resolve pages lazily: opening a 200-page volume must not download all 34 API batches first. */
internal class ComicPages(private val fetch: (Int, Int) -> JSONObject) {
    private data class Entry(val batch: ComicBatch, val expiresAt: Long)
    private val batches = object : LinkedHashMap<Pair<Int, Int>, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Int, Int>, Entry>): Boolean = size > 24
    }

    @Synchronized
    fun clear() = batches.clear()

    @Synchronized
    fun batch(chapterId: Int, skip: Int): ComicBatch {
        val key = chapterId to skip
        val cached = batches[key]
        if (cached != null && cached.expiresAt > System.nanoTime()) return cached.batch
        val result = ComicBatch.parse(fetch(chapterId, skip), chapterId, skip)
        batches[key] = Entry(result, System.nanoTime() + 5 * 60 * 1_000_000_000L)
        return result
    }

    fun image(chapterId: Int, index: Int): String {
        if (index < 0) throw IOException("无效的漫画页码。")
        val batch = batch(chapterId, index / BATCH_SIZE * BATCH_SIZE)
        return batch.images.getOrNull(index - batch.skip)
            ?: throw IOException("轻书架未返回此页图片，请刷新章节。")
    }

    companion object { const val BATCH_SIZE = 6 }
}
