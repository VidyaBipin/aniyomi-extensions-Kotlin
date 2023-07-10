package eu.kanade.tachiyomi.animeextension.pt.hentaistube

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable

class HentaisTube : ParsedAnimeHttpSource() {

    override val name = "HentaisTube"

    override val baseUrl = "https://www.hentaistube.com"

    override val lang = "pt-BR"

    override val supportsLatest = false

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/ranking-hentais?paginacao=$page", headers)

    override fun popularAnimeSelector() = "ul.ul_sidebar > li"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        thumbnail_url = element.selectFirst("img")!!.attr("src")
        element.selectFirst("div.rt a.series")!!.also {
            setUrlWithoutDomain(it.attr("href"))
            title = it.text().substringBefore(" - Episódios")
        }
    }

    override fun popularAnimeNextPageSelector() = "div.paginacao > a:contains(»)"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        throw UnsupportedOperationException("Not used.")
    }

    override fun latestUpdatesSelector(): String {
        throw UnsupportedOperationException("Not used.")
    }

    override fun latestUpdatesFromElement(element: Element): SAnime {
        throw UnsupportedOperationException("Not used.")
    }

    override fun latestUpdatesNextPageSelector(): String? {
        throw UnsupportedOperationException("Not used.")
    }

    // =============================== Search ===============================
    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/$id"))
                .asObservableSuccess()
                .map(::searchAnimeByIdParse)
        } else {
            super.fetchSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.asJsoup())
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        throw UnsupportedOperationException("Not used.")
    }

    override fun searchAnimeSelector(): String {
        throw UnsupportedOperationException("Not used.")
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        throw UnsupportedOperationException("Not used.")
    }

    override fun searchAnimeNextPageSelector(): String? {
        throw UnsupportedOperationException("Not used.")
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        throw UnsupportedOperationException("Not used.")
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector(): String {
        throw UnsupportedOperationException("Not used.")
    }

    override fun episodeFromElement(element: Element): SEpisode {
        throw UnsupportedOperationException("Not used.")
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        throw UnsupportedOperationException("Not used.")
    }

    override fun videoListSelector(): String {
        throw UnsupportedOperationException("Not used.")
    }

    override fun videoFromElement(element: Element): Video {
        throw UnsupportedOperationException("Not used.")
    }

    override fun videoUrlParse(document: Document): String {
        throw UnsupportedOperationException("Not used.")
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}
