package eu.kanade.tachiyomi.animeextension.es.latanime

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.es.latanime.extractors.Mp4uploadExtractor
import eu.kanade.tachiyomi.animeextension.es.latanime.extractors.UploadExtractor
import eu.kanade.tachiyomi.animeextension.es.latanime.extractors.YourUploadExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.fembedextractor.FembedExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class Latanime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Latanime"

    override val baseUrl = "https://latanime.org"

    override val lang = "es"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeSelector(): String = "div.row > div"

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/emision?p=$page")
    }

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()

        anime.setUrlWithoutDomain(element.selectFirst("a").attr("href").toHttpUrl().encodedPath)
        anime.title = element.selectFirst("div.seriedetails > h3").text()
        anime.thumbnail_url = element.selectFirst("img").attr("src")

        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination > li.active ~ li:has(a)"

    // =============================== Latest ===============================

    override fun latestUpdatesNextPageSelector(): String = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

    // =============================== Search ===============================

    override fun searchAnimeSelector(): String = "div.row > div:has(a)"

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters

        val yearFilter = filterList.find { it is YearFilter } as YearFilter
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        val letterFilter = filterList.find { it is LetterFilter } as LetterFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/buscar?q=$query&p=$page")
            else -> {
                GET("$baseUrl/animes?fecha=${yearFilter.toUriPart()}&genero=${genreFilter.toUriPart()}&letra=${letterFilter.toUriPart()}")
            }
        }
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        YearFilter(),
        GenreFilter(),
        LetterFilter()
    )

    private class YearFilter : UriPartFilter(
        "Año",
        arrayOf(
            Pair("Seleccionar", "false"),
            Pair("2023", "2023"),
            Pair("2022", "2022"),
            Pair("2021", "2021"),
            Pair("2020", "2020"),
            Pair("2019", "2019"),
            Pair("2018", "2018"),
            Pair("2017", "2017"),
            Pair("2016", "2016"),
            Pair("2015", "2015"),
            Pair("2014", "2014"),
            Pair("2013", "2013"),
            Pair("2012", "2012"),
            Pair("2011", "2011"),
            Pair("2010", "2010"),
            Pair("2009", "2009"),
            Pair("2008", "2008"),
            Pair("2007", "2007"),
            Pair("2006", "2006"),
            Pair("2005", "2005"),
            Pair("2004", "2004"),
            Pair("2003", "2003"),
            Pair("2002", "2002"),
            Pair("2001", "2001"),
            Pair("2000", "2000"),
            Pair("1999", "1999"),
            Pair("1998", "1998"),
            Pair("1997", "1997"),
            Pair("1996", "1996"),
            Pair("1995", "1995"),
            Pair("1994", "1994"),
            Pair("1993", "1993"),
            Pair("1992", "1992"),
            Pair("1991", "1991"),
            Pair("1990", "1990"),
            Pair("1989", "1989"),
            Pair("1988", "1988"),
            Pair("1987", "1987"),
            Pair("1986", "1986"),
            Pair("1985", "1985"),
            Pair("1984", "1984"),
            Pair("1983", "1983"),
            Pair("1982", "1982")
        )
    )

    private class GenreFilter : UriPartFilter(
        "Genéros",
        arrayOf(
            Pair("Seleccionar", "false"),
            Pair("Acción", "accion"),
            Pair("Aventura", "aventura"),
            Pair("Carreras", "carreras"),
            Pair("Ciencia Ficción", "ciencia-ficcion"),
            Pair("Comedia", "comedia"),
            Pair("Cyberpunk", "cyberpunk"),
            Pair("Deportes", "deportes"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Escolares", "escolares"),
            Pair("Fantasía", "fantasia"),
            Pair("Gore", "gore"),
            Pair("Harem", "harem"),
            Pair("Horror", "horror"),
            Pair("Josei", "josei"),
            Pair("Lucha", "lucha"),
            Pair("Magia", "magia"),
            Pair("Mecha", "mecha"),
            Pair("Militar", "militar"),
            Pair("Misterio", "misterio"),
            Pair("Música", "musica"),
            Pair("Parodias", "parodias"),
            Pair("Psicológico", "psicologico"),
            Pair("Recuerdos de la vida", "recuerdos-de-la-vida"),
            Pair("Seinen", "seinen"),
            Pair("Shojo", "shojo"),
            Pair("Shonen", "shonen"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Vampiros", "vampiros"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
            Pair("Latino", "latino"),
            Pair("Espacial", "espacial"),
            Pair("Histórico", "historico"),
            Pair("Samurai", "samurai"),
            Pair("Artes Marciales", "artes-marciales"),
            Pair("Demonios", "demonios"),
            Pair("Romance", "romance"),
            Pair("Dementia", "dementia"),
            Pair("Policía", "policia"),
            Pair("Castellano", "castellano"),
            Pair("Historia paralela", "historia-paralela"),
            Pair("Aenime", "aenime"),
            Pair("Donghua", "donghua"),
            Pair("Blu-ray", "blu-ray"),
            Pair("Monogatari", "monogatari")
        )
    )

    private class LetterFilter : UriPartFilter(
        "Letra",
        arrayOf(
            Pair("Seleccionar", "false"),
            Pair("0-9", "09"),
            Pair("A", "A"),
            Pair("B", "B"),
            Pair("C", "C"),
            Pair("D", "D"),
            Pair("E", "E"),
            Pair("F", "F"),
            Pair("G", "G"),
            Pair("H", "H"),
            Pair("I", "I"),
            Pair("J", "J"),
            Pair("K", "K"),
            Pair("L", "L"),
            Pair("M", "M"),
            Pair("N", "N"),
            Pair("O", "O"),
            Pair("P", "P"),
            Pair("Q", "Q"),
            Pair("R", "R"),
            Pair("S", "S"),
            Pair("T", "T"),
            Pair("U", "U"),
            Pair("V", "V"),
            Pair("W", "W"),
            Pair("X", "X"),
            Pair("Y", "Y"),
            Pair("Z", "Z")
        )
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("div.row > div > h2").text()
        anime.genre = document.select("div.row > div > a:has(div.btn)").eachText().joinToString(separator = ", ")
        anime.description = document.selectFirst("div.row > div > p.my-2").text()
        return anime
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select(episodeListSelector()).map { episodeFromElement(it) }.reversed()
    }

    override fun episodeListSelector() = "div.row > div > div.row > div > a"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val title = element.text()
        episode.episode_number = title.substringAfter("Capitulo ").toFloatOrNull() ?: 0F
        episode.name = title.replace("- ", "")
        episode.setUrlWithoutDomain(element.attr("href").toHttpUrl().encodedPath)
        return episode
    }

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        document.select(videoListSelector()).forEach { videoElement ->
            val url = String(Base64.decode(videoElement.attr("data-player"), Base64.DEFAULT))
            val prefix = "${videoElement.text()} - "

            when {
                url.contains("fembed.com") ||
                    url.contains("anime789.com") || url.contains("24hd.club") || url.contains("fembad.org") ||
                    url.contains("vcdn.io") || url.contains("sharinglink.club") || url.contains("moviemaniac.org") ||
                    url.contains("votrefiles.club") || url.contains("femoload.xyz") || url.contains("albavido.xyz") ||
                    url.contains("feurl.com") || url.contains("dailyplanet.pw") || url.contains("ncdnstm.com") ||
                    url.contains("jplayer.net") || url.contains("xstreamcdn.com") || url.contains("fembed-hd.com") ||
                    url.contains("gcloud.live") || url.contains("vcdnplay.com") || url.contains("superplayxyz.club") ||
                    url.contains("vidohd.com") || url.contains("vidsource.me") || url.contains("cinegrabber.com") ||
                    url.contains("votrefile.xyz") || url.contains("zidiplay.com") || url.contains("ndrama.xyz") ||
                    url.contains("fcdn.stream") || url.contains("mediashore.org") || url.contains("suzihaza.com") ||
                    url.contains("there.to") || url.contains("femax20.com") || url.contains("javstream.top") ||
                    url.contains("viplayer.cc") || url.contains("sexhd.co") || url.contains("fembed.net") ||
                    url.contains("mrdhan.com") || url.contains("votrefilms.xyz") || // url.contains("") ||
                    url.contains("embedsito.com") || url.contains("dutrag.com") || // url.contains("") ||
                    url.contains("youvideos.ru") || url.contains("streamm4u.club") || // url.contains("") ||
                    url.contains("moviepl.xyz") || url.contains("asianclub.tv") || // url.contains("") ||
                    url.contains("vidcloud.fun") || url.contains("fplayer.info") || // url.contains("") ||
                    url.contains("diasfem.com") || url.contains("javpoll.com") // url.contains("")
                -> {
                    val fUrl = url.replace("\\/", "/")
                        .replace("https://www.fembed.com", "https://vanfem.com")
                    val videos = FembedExtractor(client).videosFromUrl(fUrl, prefix = prefix, redirect = true)
                    videoList.addAll(videos)
                }
                url.contains("ok.ru") -> {
                    val videos = OkruExtractor(client).videosFromUrl(url, prefix = prefix)
                    videoList.addAll(videos)
                }
                url.contains("mp4upload.com") -> {
                    val headers = headers.newBuilder().set("referer", "https://mp4upload.com/").build()
                    val videos = Mp4uploadExtractor(client).getVideoFromUrl(url, headers = headers, prefix = prefix)
                    videoList.addAll(videos)
                }
                url.contains("uqload") -> {
                    val headers = headers.newBuilder().add("referer", "https://uqload.com/").build()
                    val videos = UploadExtractor(client).videoFromUrl(url, headers = headers, prefix = prefix)
                    videoList.addAll(videos)
                }
                url.contains("doodstream") -> {
                    val videos = DoodExtractor(client).videosFromUrl(url)
                    videoList.addAll(videos)
                }
                url.contains("yourupload") -> {
                    val headers = headers.newBuilder().add("referer", "https://www.yourupload.com/").build()
                    val videos = YourUploadExtractor(client).videoFromUrl(url, headers = headers, prefix = prefix)
                    videoList.addAll(videos)
                }
                url.contains("sbembed.com") || url.contains("sbembed1.com") || url.contains("sbplay.org") ||
                    url.contains("sbvideo.net") || url.contains("streamsb.net") || url.contains("sbplay.one") ||
                    url.contains("cloudemb.com") || url.contains("playersb.com") || url.contains("tubesb.com") ||
                    url.contains("sbplay1.com") || url.contains("embedsb.com") || url.contains("watchsb.com") ||
                    url.contains("sbplay2.com") || url.contains("japopav.tv") || url.contains("viewsb.com") ||
                    url.contains("sbfast") || url.contains("sbfull.com") || url.contains("javplaya.com") ||
                    url.contains("ssbstream.net") || url.contains("p1ayerjavseen.com") || url.contains("sbthe.com") ||
                    url.contains("sbchill.com") || url.contains("sblongvu.com") || url.contains("sbanh.com") ||
                    url.contains("sblanh.com")
                -> {
                    val videos = StreamSBExtractor(client).videosFromUrl(url, headers, prefix = prefix)
                    videoList.addAll(videos)
                }
            }
        }

        return videoList.sort()
    }

    override fun videoListSelector() = "li#play-video > a.play-video"

    override fun videoFromElement(element: Element) = throw Exception("Not used")

    override fun videoUrlParse(document: Document) = throw Exception("Not used")

    // ============================= Utilities ==============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!

        return this.sortedWith(
            compareBy { it.quality.contains(quality) }
        ).reversed()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p")
            entryValues = arrayOf("1080", "720", "480", "360", "240")
            setDefaultValue("1080")
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
}
