package eu.kanade.tachiyomi.animeextension.en.zoro

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.en.zoro.extractors.ZoroExtractor
import eu.kanade.tachiyomi.animeextension.en.zoro.utils.JSONUtil
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.lang.Exception

class Zoro : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "zoro.to (experimental)"

    override val baseUrl = "https://zoro.to"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = "div.flw-item"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/most-popular?page=$page")

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        thumbnail_url = element.selectFirst("div.film-poster > img").attr("data-src")
        val filmDetail = element.selectFirst("div.film-detail a")
        setUrlWithoutDomain(filmDetail.attr("href"))
        title = filmDetail.attr("data-jname")
    }

    override fun popularAnimeNextPageSelector(): String = "li.page-item a[title=Next]"

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "ul#episode_page li a"

    override fun episodeListRequest(anime: SAnime): Request {
        val id = anime.url.substringAfterLast("-")
        val referer = Headers.headersOf("Referer", baseUrl + anime.url)
        return GET("$baseUrl/ajax/v2/episode/list/$id", referer)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val data = response.body!!.string()
            .substringAfter("\"html\":\"")
            .substringBefore("<script>")
        val unescapedData = JSONUtil.unescape(data)
        val document = Jsoup.parse(unescapedData)
        val episodeList = document.select("a.ep-item").map {
            SEpisode.create().apply {
                episode_number = it.attr("data-number").toFloat()
                name = "Episode ${it.attr("data-number")}: ${it.attr("title")}"
                url = it.attr("href")
            }
        }
        return episodeList.reversed()
    }

    override fun episodeFromElement(element: Element) = throw Exception("not used")

    // ============================ Video Links =============================
    override fun videoListRequest(episode: SEpisode): Request {
        val id = episode.url.substringAfterLast("?ep=")
        val referer = Headers.headersOf("Referer", baseUrl + episode.url)
        return GET("$baseUrl/ajax/v2/episode/servers?episodeId=$id", referer)
    }

    override fun videoListParse(response: Response): List<Video> {
        val body = response.body!!.string()
        val episodeReferer = Headers.headersOf("Referer", response.request.header("referer")!!)
        val data = body.substringAfter("\"html\":\"").substringBefore("<script>")
        val unescapedData = JSONUtil.unescape(data)
        val serversHtml = Jsoup.parse(unescapedData)
        val ignoredServers = listOf("StreamSB", "StreamTape")
        val extractor = ZoroExtractor(client)
        val videoList = serversHtml.select("div.server-item")
            .filterNot { it.text() in ignoredServers }
            .parallelMap { server ->
                val id = server.attr("data-id")
                val subDub = server.attr("data-type")
                val url = "$baseUrl/ajax/v2/episode/sources?id=$id"
                val reqBody = client.newCall(GET(url, episodeReferer)).execute()
                    .body!!.string()
                val sourceUrl = reqBody.substringAfter("\"link\":\"")
                    .substringBefore("\"") + "&autoPlay=1&oa=0"
                runCatching {
                    val source = extractor.getSourcesJson(sourceUrl)
                    source?.let { getVideosFromServer(it, subDub) }
                }.getOrNull()
            }
            .filterNotNull()
            .flatten()
        return videoList
    }

    private fun getVideosFromServer(source: String, subDub: String): List<Video>? {
        if (!source.contains("{\"sources\":[{\"file\":\"")) return null
        val json = json.decodeFromString<JsonObject>(source)
        val masterUrl = json["sources"]!!.jsonArray[0].jsonObject["file"]!!.jsonPrimitive.content
        val subs2 = mutableListOf<Track>()
        json["tracks"]?.jsonArray
            ?.filter { it.jsonObject["kind"]!!.jsonPrimitive.content == "captions" }
            ?.map { track ->
                val trackUrl = track.jsonObject["file"]!!.jsonPrimitive.content
                val lang = track.jsonObject["label"]!!.jsonPrimitive.content
                try {
                    subs2.add(Track(trackUrl, lang))
                } catch (e: Error) {}
            } ?: emptyList()
        val subs = subLangOrder(subs2)
        val prefix = "#EXT-X-STREAM-INF:"
        val playlist = client.newCall(GET(masterUrl)).execute()
            .body!!.string()
        val videoList = playlist.substringAfter(prefix).split(prefix).map {
            val quality = it.substringAfter("RESOLUTION=")
                .substringAfter("x")
                .substringBefore(",") + "p - $subDub"
            val videoUrl = masterUrl.substringBeforeLast("/") + "/" +
                it.substringAfter("\n").substringBefore("\n")
            try {
                Video(videoUrl, quality, videoUrl, subtitleTracks = subs)
            } catch (e: Error) {
                Video(videoUrl, quality, videoUrl)
            }
        }
        return videoList
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    private fun List<Video>.sortIfContains(item: String): List<Video> {
        val newList = mutableListOf<Video>()
        var preferred = 0
        for (video in this) {
            if (item in video.quality) {
                newList.add(preferred, video)
                preferred++
            } else {
                newList.add(video)
            }
        }
        return newList
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, "720p")!!
        val type = preferences.getString(PREF_TYPE_KEY, "dub")!!
        val newList = this.sortIfContains(type).sortIfContains(quality)
        return newList
    }

    private fun subLangOrder(tracks: List<Track>): List<Track> {
        val language = preferences.getString(PREF_SUB_KEY, null)
        if (language != null) {
            val newList = mutableListOf<Track>()
            var preferred = 0
            for (track in tracks) {
                if (track.lang == language) {
                    newList.add(preferred, track)
                    preferred++
                } else {
                    newList.add(track)
                }
            }
            return newList
        }
        return tracks
    }

    // =============================== Search =============================== 
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = GET("$baseUrl/search?keyword=$query&page=$page")

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val info = document.selectFirst("div.anisc-info")
        anime.thumbnail_url = document.selectFirst("div.anisc-poster img").attr("src")
        anime.title = document.selectFirst("div.anisc-detail h2").attr("data-jname")
        anime.author = info.getInfo("Studios:")
        anime.status = parseStatus(info.getInfo("Status:"))
        anime.genre = info.getInfo("Genres:", isList = true)
        var description = (info.getInfo("Overview:") + "\n") ?: ""
        info.getInfo("Aired:", full = true)?.let { description += it }
        info.getInfo("Premiered:", full = true)?.let { description += it }
        info.getInfo("Synonyms:", full = true)?.let { description += it }
        info.getInfo("Japanese:", full = true)?.let { description += it }
        anime.description = description
        return anime
    }

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/top-airing")

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {

        val videoQualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue("720p")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        val epTypePref = ListPreference(screen.context).apply {
            key = PREF_TYPE_KEY
            title = PREF_TYPE_TITLE
            entries = PREF_TYPE_ENTRIES
            entryValues = PREF_TYPE_ENTRIES
            setDefaultValue("dub")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        val subLangPref = ListPreference(screen.context).apply {
            key = PREF_SUB_KEY
            title = PREF_SUB_TITLE
            entries = PREF_SUB_ENTRIES
            entryValues = PREF_SUB_ENTRIES
            setDefaultValue("English")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
        screen.addPreference(epTypePref)
        screen.addPreference(subLangPref)
    }

    // ============================= Utilities ==============================

    private fun parseStatus(statusString: String?): Int {
        return when (statusString) {
            "Currently Airing" -> SAnime.ONGOING
            "Finished Airing" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    private fun Element.getInfo(
        tag: String,
        isList: Boolean = false,
        full: Boolean = false
    ): String? {
        if (isList) {
            val elements = select("div.item-list:contains($tag) > a")
            return elements.joinToString(", ") { it.text() }
        }
        val targetElement = selectFirst("div.item-title:contains($tag)")
            ?: return null
        val value = targetElement.selectFirst("*.name, *.text")!!.text()
        return if (full) "\n$tag $value" else value
    }

    fun <A, B> Iterable<A>.parallelMap(f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }

    companion object {

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred video quality"
        private val PREF_QUALITY_ENTRIES = arrayOf("360p", "720p", "1080p")

        private const val PREF_TYPE_KEY = "preferred_type"
        private const val PREF_TYPE_TITLE = "Preferred episode type/mode"
        private val PREF_TYPE_ENTRIES = arrayOf("sub", "dub")

        private const val PREF_SUB_KEY = "preferred_subLang"
        private const val PREF_SUB_TITLE = "Preferred sub language"
        private val PREF_SUB_ENTRIES = arrayOf(
            "English", "Spanish", "Portuguese", "French",
            "German", "Italian", "Japanese", "Russian"
        )
    }
}
