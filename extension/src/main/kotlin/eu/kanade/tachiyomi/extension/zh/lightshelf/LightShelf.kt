package eu.kanade.tachiyomi.extension.zh.lightshelf

import android.app.Application
import android.content.Context
import android.text.InputType
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.time.Instant

class LightShelf : HttpSource(), ConfigurableSource {
    override val name = "轻书架 / LightShelf"
    override val baseUrl = LightShelfApi.WEBSITE
    override val lang = "zh"
    override val supportsLatest = true

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", Context.MODE_PRIVATE)
    }
    private val savedPassword by lazy { SavedPassword("lightshelf_${id}_password") }
    private fun password(): String = preferences.getString("password_encrypted", null)?.let(savedPassword::decrypt).orEmpty()
    private val session by lazy {
        object : SessionStore {
            override var refreshToken: String
                get() = preferences.getString("refresh_token", "").orEmpty()
                set(value) { preferences.edit().putString("refresh_token", value).apply() }
            override val tokenOnly get() = preferences.getBoolean("custom_token", false)
            override val email get() = preferences.getString("email", "").orEmpty()
            override val passwordHash get() = if (preferences.contains("password_encrypted")) {
                try { password().let(LightShelfApi::passwordHash) }
                catch (_: Exception) { throw IOException("保存的密码无法读取，请在源设置中重新输入密码。") }
            } else preferences.getString("password_hash", "").orEmpty()
            override val visitorId: String = preferences.getString("visitor_id", null)
                ?: LightShelfApi.newVisitorId().also { preferences.edit().putString("visitor_id", it).apply() }
        }
    }
    private val serverLines = linkedMapOf(
        "https://api.lightnovel.life" to "香港（hk）",
        "https://cf-api.lightnovel.life" to "Cloudflare",
    )
    private fun endpoint() = preferences.getString("api_server", null)
        ?.takeIf { it in serverLines } ?: serverLines.keys.first()
    private val api by lazy { LightShelfApi(client, session, endpoint()) }
    private val catalog by lazy { ComicCatalog(api::call) }
    private val comicPages by lazy {
        ComicPages { chapter, skip ->
            api.call("GetComicContent", json("Cid" to chapter, "Skip" to skip, "Take" to ComicPages.BATCH_SIZE))
        }
    }

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = Observable.fromCallable {
        parseList(api.call("GetComicList", json("Page" to page, "Size" to 24, "Order" to "view")))
    }

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = Observable.fromCallable {
        parseList(api.call("GetComicList", json("Page" to page, "Size" to 24, "Order" to "latest")))
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> =
        if (query.isBlank()) fetchPopularManga(page) else Observable.fromCallable {
            parseList(
                api.call(
                    "SearchComicSeries",
                    json("KeyWords" to query.trim(), "Mode" to "fuzzy", "Page" to page, "Size" to 24),
                ),
            )
        }

    private fun parseList(response: JSONObject): MangasPage = MangasPage(
        response.array("Data").objects().map { item ->
            SManga.create().apply {
                title = item.string("Title")
                url = baseUrl.toHttpUrl().newBuilder().addPathSegment("manga").addPathSegment(title).build().encodedPath
                thumbnail_url = item.optionalString("Cover").takeIf { it.isNotBlank() }
            }
        },
        response.number("Page") < response.number("TotalPages"),
    )

    private fun series(manga: SManga): JSONObject {
        val title = (baseUrl + manga.url).toHttpUrl().pathSegments.getOrNull(1)
            ?: throw IOException("无效的漫画系列地址。")
        return catalog.series(title)
    }

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable.fromCallable {
        val info = series(manga).obj("Series")
        val classification = (info.value("Extra") as? JSONObject)?.value("classification") as? JSONObject
        SManga.create().apply {
            url = manga.url
            title = info.string("Title")
            author = info.optionalString("Author").ifBlank { classification?.optionalString("author").orEmpty() }
            thumbnail_url = info.optionalString("Cover").takeIf { it.isNotBlank() }
            description = Jsoup.parse(info.optionalString("Introduction")).wholeText().trim()
            val tags = classification?.value("tags") as? org.json.JSONArray
            genre = tags?.let { (0 until it.length()).joinToString(", ") { index -> it.getString(index) } }
            status = SManga.UNKNOWN
            initialized = true
        }
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        ComicChapters.parse(series(manga)).map { chapter ->
            SChapter.create().apply {
                url = "/manga/${chapter.bookId}/read/${chapter.id}"
                name = chapter.title
                chapter_number = chapter.number
                scanlator = chapter.uploader
                date_upload = runCatching { Instant.parse(chapter.createdAt).toEpochMilli() }.getOrDefault(0L)
            }
        }
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.fromCallable {
        val chapterId = chapter.url.substringAfterLast('/').toIntOrNull()
            ?: throw IOException("无效的漫画章节地址。")
        val first = comicPages.batch(chapterId, 0)
        if (first.total == 0) throw IOException("此漫画章节暂无图片。")
        // Every page is represented; later batches are fetched as Mihon requests their image URLs.
        (0 until first.total).map { index -> Page(index, "$baseUrl${chapter.url}?page=$index") }
    }

    override fun fetchImageUrl(page: Page): Observable<String> = Observable.fromCallable {
        val url = page.url.toHttpUrl()
        val chapter = url.pathSegments.last().toIntOrNull() ?: throw IOException("无效的漫画章节地址。")
        val index = url.queryParameter("page")?.toIntOrNull() ?: throw IOException("无效的漫画页码。")
        comicPages.image(chapter, index)
    }

    override fun getFilterList() = FilterList()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        fun changed(update: () -> Unit) {
            synchronized(api) { api.close(); update() }
            comicPages.clear()
        }
        fun accountSummary() = when {
            preferences.contains("password_encrypted") -> "已保存；打开可查看和修改"
            preferences.getString("password_hash", "").orEmpty().isNotEmpty() -> "旧版密码可继续登录；重新输入一次后可自动填入"
            else -> "未设置"
        }
        fun clearToken() { preferences.edit().remove("refresh_token").remove("custom_token").apply() }
        screen.addPreference(Preference(screen.context).apply {
            title = "漫画账号登录"
            summary = "邮箱和密码会保存，返回漫画列表即可登录；也可直接使用刷新 Token。"
            isSelectable = false
        })
        screen.addPreference(ListPreference(screen.context).apply {
            key = "api_server"
            title = "服务器线路"
            entries = serverLines.values.toTypedArray()
            entryValues = serverLines.keys.toTypedArray()
            isPersistent = false
            value = endpoint()
            summary = serverLines[value]
            setOnPreferenceChangeListener { _, selected ->
                val line = selected.toString()
                if (line in serverLines && line != endpoint()) {
                    synchronized(api) {
                        api.setEndpoint(line)
                        preferences.edit().putString("api_server", line).apply()
                    }
                    comicPages.clear()
                    value = line
                    summary = serverLines[line]
                }
                false
            }
        })
        screen.addPreference(EditTextPreference(screen.context).apply {
            key = "email"
            title = "邮箱"
            summary = session.email.ifBlank { "未设置" }
            isPersistent = false
            text = session.email
            setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
            setOnPreferenceChangeListener { _, value ->
                val email = value.toString().trim()
                if (email != session.email) changed {
                    preferences.edit().putString("email", email).apply()
                    clearToken()
                }
                text = email
                summary = email.ifBlank { "未设置" }
                false
            }
        })
        screen.addPreference(EditTextPreference(screen.context).apply {
            key = "password_input"
            title = "密码"
            summary = accountSummary()
            isPersistent = false
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                it.setText(runCatching { password() }.getOrDefault(""))
                it.setSelection(it.text.length)
            }
            setOnPreferenceChangeListener { _, value ->
                val plain = value.toString()
                try {
                    if (plain != runCatching { password() }.getOrNull() || !preferences.contains("password_encrypted")) {
                        val encrypted = plain.takeIf { it.isNotEmpty() }?.let(savedPassword::encrypt)
                        changed {
                            preferences.edit().putString("password_encrypted", encrypted).remove("password_hash").apply()
                            clearToken()
                        }
                    }
                    summary = accountSummary()
                } catch (_: Exception) {
                    Toast.makeText(screen.context, "密码保存失败，请重新输入。", Toast.LENGTH_LONG).show()
                }
                false
            }
        })
        screen.addPreference(EditTextPreference(screen.context).apply {
            key = "refresh_token_input"
            title = "自定义 Token"
            summary = "使用刷新令牌（RefreshToken）；登录后自动填入，留空则使用邮箱和密码"
            isPersistent = false
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                it.setText(session.refreshToken)
                it.setSelection(it.text.length)
            }
            setOnPreferenceChangeListener { _, value ->
                val token = value.toString().trim()
                if (token != session.refreshToken) changed {
                    preferences.edit().putString("refresh_token", token).putBoolean("custom_token", token.isNotEmpty()).apply()
                }
                false
            }
        })
        screen.addPreference(Preference(screen.context).apply {
            title = "清除登录信息"
            summary = "清除邮箱、保存的密码和 Token"
            setOnPreferenceClickListener {
                changed {
                    preferences.edit().remove("email").remove("password_hash").remove("password_encrypted").apply()
                    clearToken()
                }
                screen.findPreference<EditTextPreference>("email")?.apply { text = ""; summary = "未设置" }
                screen.findPreference<EditTextPreference>("password_input")?.summary = "未设置"
                true
            }
        })
    }

    // HttpSource's HTML request/parser hooks are unused: this source calls a SignalR hub.
    private fun signalR(): Nothing = throw UnsupportedOperationException("轻书架漫画使用 SignalR 接口。")
    override fun popularMangaRequest(page: Int): Request = signalR()
    override fun popularMangaParse(response: Response): MangasPage = signalR()
    override fun latestUpdatesRequest(page: Int): Request = signalR()
    override fun latestUpdatesParse(response: Response): MangasPage = signalR()
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = signalR()
    override fun searchMangaParse(response: Response): MangasPage = signalR()
    override fun mangaDetailsParse(response: Response): SManga = signalR()
    override fun chapterListParse(response: Response): List<SChapter> = signalR()
    override fun pageListParse(response: Response): List<Page> = signalR()
    override fun imageUrlParse(response: Response): String = signalR()
}
