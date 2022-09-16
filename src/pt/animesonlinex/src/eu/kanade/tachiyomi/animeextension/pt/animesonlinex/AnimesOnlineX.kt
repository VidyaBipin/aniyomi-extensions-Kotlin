package eu.kanade.tachiyomi.animeextension.pt.animesonlinex

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.pt.animesonlinex.extractors.GuiaNoticiarioBypasser
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception
import java.text.SimpleDateFormat
import java.util.Locale

class AnimesOnlineX : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimesOnlineX"

    override val baseUrl = "https://animesonlinex.cc"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override val client: OkHttpClient = network.client

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Referer", baseUrl)
        .add("Accept-Language", AOXConstants.ACCEPT_LANGUAGE)
        .add("User-Agent", AOXConstants.USER_AGENT)

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = "article.w_item_a > a"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/animes/")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val img = element.selectFirst("img")
        val url = element.selectFirst("a")?.attr("href") ?: element.attr("href")
        anime.setUrlWithoutDomain(url)
        anime.title = img.attr("alt")
        anime.thumbnail_url = img.attr("src")
        return anime
    }

    override fun popularAnimeNextPageSelector() = throw Exception("not used")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(popularAnimeSelector()).map { element ->
            popularAnimeFromElement(element)
        }
        return AnimesPage(animes, false)
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector(): String = "ul.episodios > li"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = getRealDoc(response.asJsoup())
        val epList = doc.select(episodeListSelector())
        if (epList.size < 1) {
            val episode = SEpisode.create()
            episode.setUrlWithoutDomain(response.request.url.toString())
            episode.episode_number = 1F
            episode.name = "Filme"
            return listOf(episode)
        }
        return epList.reversed().map { episodeFromElement(it) }
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val origName = element.selectFirst("div.numerando").text()

        episode.episode_number = origName.substring(origName.indexOf("-") + 1)
            .toFloat() + if ("Dub" in origName) 0.5F else 0F
        episode.name = "Temp " + origName.replace(" - ", ": Ep ")
        episode.setUrlWithoutDomain(element.selectFirst("a").attr("href"))
        episode.date_upload = element.selectFirst("span.date")?.text()?.toDate() ?: 0L
        return episode
    }

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val urls = document.select("div.source-box:not(#source-player-trailer) div.pframe a")
            .map { it.attr("href") }
        val resolutions = document.select("ul#playeroptionsul > li > span.resol")
            .map { it.text() }
        val videoList = mutableListOf<Video>()
        urls.forEachIndexed { index, it ->
            val url = GuiaNoticiarioBypasser(client, headers).fromUrl(it)
            videoList.addAll(getPlayerVideos(url, resolutions.get(index)))
        }
        return videoList
    }

    private fun getPlayerVideos(url: String, quality: String): List<Video> {

        return when {
            "/vplayer/?source" in url -> {
                val videoUrl = Uri.parse(url).getQueryParameter("source")!!
                listOf(Video(videoUrl, "VPlayer", videoUrl))
            }
            else -> emptyList<Video>()
        }
    }

    override fun videoListSelector() = throw Exception("not used")
    override fun videoFromElement(element: Element) = throw Exception("not used")
    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // =============================== Search ===============================
    private fun searchAnimeBySlugParse(response: Response, slug: String): AnimesPage {
        val details = animeDetailsParse(response)
        details.url = "/animes/$slug"
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val url = response.request.url.toString()
        val document = response.asJsoup()

        val animes = when {
            "/generos/" in url -> {
                document.select(latestUpdatesSelector()).map { element ->
                    popularAnimeFromElement(element)
                }
            }
            else -> {
                document.select(searchAnimeSelector()).map { element ->
                    searchAnimeFromElement(element)
                }
            }
        }

        val hasNextPage = document.selectFirst(searchAnimeNextPageSelector()) != null
        return AnimesPage(animes, hasNextPage)
    }

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(AOXConstants.PREFIX_SEARCH)) {
            val slug = query.removePrefix(AOXConstants.PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/animes/$slug", headers))
                .asObservableSuccess()
                .map { response ->
                    searchAnimeBySlugParse(response, slug)
                }
        } else {
            val params = AOXFilters.getSearchParameters(filters)
            client.newCall(searchAnimeRequest(page, query, params))
                .asObservableSuccess()
                .map { response ->
                    searchAnimeParse(response)
                }
        }
    }

    override fun getFilterList(): AnimeFilterList = AOXFilters.filterList

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = throw Exception("not used")

    private fun searchAnimeRequest(page: Int, query: String, filters: AOXFilters.FilterSearchParams): Request {
        return when {
            query.isBlank() -> {
                val genre = filters.genre
                var url = "$baseUrl/generos/$genre"
                if (page > 1) url += "/page/$page"
                GET(url, headers)
            }
            else -> GET("$baseUrl/page/$page/?s=$query", headers)
        }
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.text()
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = latestUpdatesNextPageSelector()

    override fun searchAnimeSelector(): String = "div.result-item div.details div.title a"

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val doc = getRealDoc(document)
        val sheader = doc.selectFirst("div.sheader")
        val img = sheader.selectFirst("div.poster > img")
        anime.thumbnail_url = img.attr("src")
        anime.title = sheader.selectFirst("div.data > h1").text()
        val status = sheader.selectFirst("div.alert")
        anime.status = parseStatus(status?.text())
        anime.genre = sheader.select("div.data > div.sgeneros > a")
            .joinToString(", ") { it.text() }
        val info = doc.selectFirst("div#info")
        var description = info.selectFirst("p").text() + "\n"
        status?.let { description += "\n$it" }
        info.getInfo("Título")?.let { description += "$it" }
        info.getInfo("Ano")?.let { description += "$it" }
        info.getInfo("Temporadas")?.let { description += "$it" }
        info.getInfo("Episódios")?.let { description += "$it" }
        anime.description = description
        return anime
    }

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector(): String = "div.resppages > a > span.fa-chevron-right"

    override fun latestUpdatesSelector(): String = "div.content article > div.poster"

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/episodio/page/$page", headers)

    // ============================== Settings ============================== 
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = AOXConstants.PREFERRED_QUALITY
            title = "Qualidade preferida"
            entries = AOXConstants.QUALITY_LIST
            entryValues = AOXConstants.QUALITY_LIST
            setDefaultValue(AOXConstants.DEFAULT_QUALITY)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        screen.addPreference(videoQualityPref)
    }

    // ============================= Utilities ==============================
    private val animeMenuSelector = "div.pag_episodes div.item a[href] i.fa-bars"

    private fun getRealDoc(document: Document): Document {
        val menu = document.selectFirst(animeMenuSelector)
        if (menu != null) {
            val originalUrl = menu.parent().attr("href")
            val req = client.newCall(GET(originalUrl, headers)).execute()
            return req.asJsoup()
        } else {
            return document
        }
    }

    private fun parseStatus(status: String?): Int {
        return when (status) {
            null -> SAnime.COMPLETED
            else -> SAnime.ONGOING
        }
    }

    private fun Element.getInfo(substring: String): String? {
        val target = this.selectFirst("div.custom_fields:contains($substring)")
            ?: return null
        val key = target.selectFirst("b").text()
        val value = target.selectFirst("span").text()
        return "\n$key: $value"
    }

    private fun String.toDate(): Long {
        return runCatching { DATE_FORMATTER.parse(trim())?.time }
            .getOrNull() ?: 0L
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(AOXConstants.PREFERRED_QUALITY, AOXConstants.DEFAULT_QUALITY)!!
        val newList = mutableListOf<Video>()
        var preferred = 0
        for (video in this) {
            if (quality in video.quality) {
                newList.add(preferred, video)
                preferred++
            } else {
                newList.add(video)
            }
        }
        return newList
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy", Locale.ENGLISH)
        }
    }
}
