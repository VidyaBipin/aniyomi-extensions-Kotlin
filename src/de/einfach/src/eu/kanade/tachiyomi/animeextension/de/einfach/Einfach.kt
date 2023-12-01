package eu.kanade.tachiyomi.animeextension.de.einfach

import android.util.Base64
import eu.kanade.tachiyomi.animeextension.de.einfach.extractors.MyStreamExtractor
import eu.kanade.tachiyomi.animeextension.de.einfach.extractors.UnpackerExtractor
import eu.kanade.tachiyomi.animeextension.de.einfach.extractors.VidozaExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Locale

class Einfach : ParsedAnimeHttpSource() {

    override val name = "Einfach"

    override val baseUrl = "https://einfach.to"

    override val lang = "de"

    override val supportsLatest = true

    // ============================== Popular ===============================
    // Actually the source doesn't provide a popular entries page, and the
    // "sort by views" filter isn't working, so we'll use the latest series updates instead.
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/series/page/$page")

    override fun popularAnimeSelector() = "article.box > div.bx > a.tip"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.attr("title")
        thumbnail_url = element.selectFirst("img")?.run {
            absUrl("data-lazy-src").ifEmpty { absUrl("src") }
        }
    }

    override fun popularAnimeNextPageSelector() = "div.pagination > a.next"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/filme/page/$page")

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val path = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/$path"))
                .asObservableSuccess()
                .map(::searchAnimeByPathParse)
        } else {
            super.fetchSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByPathParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.use { it.asJsoup() })
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) =
        GET("$baseUrl/page/$page/?s=$query")

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        val info = document.selectFirst("article div > div.infl")!!
        title = info.selectFirst("h1.entry-title")!!.text()
        thumbnail_url = info.selectFirst("img")?.run {
            absUrl("data-lazy-src").ifEmpty { absUrl("src") }
        }

        artist = info.getInfo("Stars:")
        genre = info.getInfo("Genre:")
        author = info.getInfo("Network:")
        status = parseStatus(info.getInfo("Status:").orEmpty())

        description = info.selectFirst("div.entry-content > p")?.ownText()
    }

    private fun Element.getInfo(label: String) =
        selectFirst("li:has(b:contains($label)) > span.colspan")?.text()?.trim()

    private fun parseStatus(status: String) = when (status) {
        "Ongoing" -> SAnime.ONGOING
        else -> SAnime.COMPLETED
    }

    // ============================== Episodes ==============================
    override fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> {
        if (anime.url.contains("/filme/")) {
            val episode = SEpisode.create().apply {
                url = anime.url
                name = "Movie - ${anime.title}"
                episode_number = 1F
            }
            return Observable.just(listOf(episode))
        }

        return super.fetchEpisodeList(anime)
    }

    override fun episodeListParse(response: Response) =
        super.episodeListParse(response).reversed()

    override fun episodeListSelector() = "div.epsdlist > ul > li > a"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        val eplnum = element.selectFirst(".epl-num")?.text().orEmpty().trim()
        episode_number = eplnum.substringAfterLast(" ").toFloatOrNull() ?: 1F

        name = eplnum.ifBlank { "S1 EP 1" } + " - " + element.selectFirst(".epl-title")?.text().orEmpty()
        date_upload = element.selectFirst(".epl-date")?.text().orEmpty().toDate()
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val doc = response.use { it.asJsoup() }

        val links = doc.select(videoListSelector()).mapNotNull { element ->
            val html = element.attr("data-em").let { b64encoded ->
                runCatching {
                    String(Base64.decode(b64encoded, Base64.DEFAULT))
                }.getOrNull()
            }

            val url = html?.let(Jsoup::parseBodyFragment)
                ?.selectFirst("iframe")
                ?.attr("src")
                ?: return@mapNotNull null

            val fixedUrl = url.takeIf { it.startsWith("https:") } ?: "https:$url"

            element.text().lowercase() to fixedUrl
        }

        return links.parallelCatchingFlatMap { (name, link) ->
            getVideosFromUrl(name, link)
        }
    }

    override fun videoListSelector() = "div.lserv > ul > li > a"

    private val doodExtractor by lazy { DoodExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val lulustreamExtractor by lazy { UnpackerExtractor(client, headers) }
    private val mixdropExtractor by lazy { MixDropExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val vidozaExtractor by lazy { VidozaExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client) }
    private val mystreamExtractor by lazy { MyStreamExtractor(client, headers) }

    private fun getVideosFromUrl(name: String, url: String): List<Video> {
        return when (name) {
            "doodstream" -> doodExtractor.videosFromUrl(url)
            "filelions" -> streamwishExtractor.videosFromUrl(url, videoNameGen = { "FileLions - $it" })
            "filemoon" -> filemoonExtractor.videosFromUrl(url)
            "lulustream" -> lulustreamExtractor.videosFromUrl(url, "LuLuStream")
            "mixdrop" -> mixdropExtractor.videosFromUrl(url)
            "streamtape" -> streamtapeExtractor.videosFromUrl(url)
            "streamwish" -> streamwishExtractor.videosFromUrl(url)
            "vidoza" -> vidozaExtractor.videosFromUrl(url)
            "voe" -> voeExtractor.videosFromUrl(url)
            "stream in hd" -> mystreamExtractor.videosFromUrl(url)
            else -> emptyList()
        }
    }

    override fun videoFromElement(element: Element): Video {
        throw UnsupportedOperationException("Not used.")
    }

    override fun videoUrlParse(document: Document): String {
        throw UnsupportedOperationException("Not used.")
    }

    // ============================= Utilities ==============================
    private fun String.toDate(): Long {
        return runCatching { DATE_FORMATTER.parse(trim())?.time }
            .getOrNull() ?: 0L
    }

    private inline fun <A, B> Iterable<A>.parallelCatchingFlatMap(crossinline f: suspend (A) -> Iterable<B>): List<B> =
        runBlocking {
            map {
                async(Dispatchers.Default) {
                    runCatching { f(it) }.getOrElse { emptyList() }
                }
            }.awaitAll().flatten()
        }

    companion object {
        const val PREFIX_SEARCH = "path:"

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH)
        }
    }
}
