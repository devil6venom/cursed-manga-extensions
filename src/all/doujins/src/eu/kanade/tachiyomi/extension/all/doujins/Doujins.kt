package eu.kanade.tachiyomi.extension.all.doujins

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

@Source
abstract class Doujins : KeiSource() {

    override val supportsLatest = true

    override suspend fun getPopularManga(page: Int): MangasPage {
        val response = client.get("$baseUrl/top/month")
        return parseGalleryPage(response.asJsoup())
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val response = client.get(getLatestPageUrl(page))
        val data = response.parseAs<DoujinsLatestResponse>()
        return MangasPage(
            data.folders.map { folder ->
                SManga.create().apply {
                    url = folder.link
                    title = folder.name
                    artist = folder.artistList
                    author = artist
                    genre = folder.tags.joinToString { it.tag }
                    thumbnail_url = folder.thumbnail2
                }
            },
            true,
        )
    }

    private fun getLatestPageUrl(page: Int): String {
        val endDate = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            add(Calendar.DATE, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DATE, -PAGE_DAYS * (page - 1))
        }

        val endDateSec = endDate.timeInMillis / 1000
        val startDateSec = endDate.apply {
            add(Calendar.DATE, -PAGE_DAYS)
        }.timeInMillis / 1000

        return "$baseUrl/folders?start=$startDateSec&end=$endDateSec"
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val seriesFilter = filterList.findInstance<SeriesFilter>()!!
        val sortFilter = filterList.findInstance<SortFilter>()!!
        val popularityPeriodFilter = filterList.findInstance<PopularityPeriodFilter>()!!

        val url = when {
            query.isNotEmpty() -> {
                baseUrl.toHttpUrl().newBuilder()
                    .addPathSegment("searches")
                    .addQueryParameter("words", query)
                    .addQueryParameter("page", page.toString())
                    .addQueryParameter("sort", sortFilter.toUriPart())
                    .build()
            }
            seriesFilter.toUriPart().isNotEmpty() -> {
                "$baseUrl${seriesFilter.toUriPart()}".toHttpUrl().newBuilder()
                    .addQueryParameter("sort", sortFilter.toUriPart())
                    .build()
            }
            else -> {
                "$baseUrl${popularityPeriodFilter.toUriPart()}".toHttpUrl()
            }
        }

        val response = client.get(url.toString())
        return parseGalleryPage(response.asJsoup())
    }

    private fun parseGalleryPage(document: Document): MangasPage {
        val pagination = document.selectFirst(".pagination")
        val mangas = document.select("div:not(.premium-folder) > .thumbnail-doujin a.gallery-visited-from-favorites").map {
            SManga.create().apply {
                setUrlWithoutDomain(it.attr("href"))
                title = it.select("div.title .text").text()
                artist = it.parent()!!.nextElementSibling()!!.select(".single-line strong").last()
                    ?.text()?.substringAfter("Artist: ")
                author = artist
                thumbnail_url = it.select("img").attr("abs:srcset").ifEmpty { it.select("img").attr("abs:src") }
            }
        }
        val hasNextPage = pagination?.selectFirst("li.page-item:last-child:not(.disabled)") != null
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val response = client.get("$baseUrl${manga.url}")
        val document = response.asJsoup()

        val updatedManga = if (fetchDetails) {
            SManga.create().apply {
                title = document.select(".folder-title a").last()!!.text()
                artist = document.select(".gallery-artist a").joinToString { it.text() }
                author = artist
                genre = document.select(".tag-area").first()!!.select("a").joinToString { it.text() }
            }
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            listOf(
                SChapter.create().apply {
                    name = "Chapter"
                    scanlator = document.selectFirst("div.folder-message:contains(Translated)")?.text()?.substringAfter("by:")?.trim()
                    url = manga.url

                    val dateAndPageCountString = document.select(".text-md-right.text-sm-left > .folder-message").text()
                    val date = dateAndPageCountString.substringBefore(" • ")
                    date_upload = parseDate(date)
                },
            )
        } else {
            chapters
        }

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.get("$baseUrl${chapter.url}")
        val document = response.asJsoup()
        return document.select(".doujin").mapIndexed { i, page ->
            Page(i, imageUrl = page.attr("abs:data-file"))
        }
    }

    private val dateFormat = DateTimeFormatter.ofPattern("MMMM dd, yyyy", Locale.US)

    private fun parseDate(dateStr: String): Long {
        val cleanDate = dateStr.replace(Regex("(?<=\\d)(st|nd|rd|th)"), "")
        return runCatching {
            LocalDate.parse(cleanDate, dateFormat).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }.getOrDefault(0L)
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("Text search ignores series and period filters"),
        Filter.Separator(),
        Filter.Header("Series filter overrides period filter"),
        SeriesFilter(),
        Filter.Separator(),
        Filter.Header("Period filter only applies at initial page"),
        PopularityPeriodFilter(),
        Filter.Separator(),
        Filter.Header("Sort only works with text search and series filter"),
        SortFilter(),
    )

    private class SeriesFilter :
        UriPartFilter(
            "Series",
            arrayOf(
                Pair("None", ""),
                Pair("Doujins - Original Series", "/doujins-original-series-19934"),
                Pair("Hentai Magazine Chapters", "/hentai-magazine-chapters-2766"),
                Pair("Hentai Manga", "/hentai-manga-19"),
                Pair("Fate Grand Order", "/fate-grand-order-doujins-28615"),
                Pair("CG Sets - Original Series", "/cg-sets-original-series-14865"),
                Pair("Touhou", "/touhou-doujins-7748"),
                Pair("Naruto", "/naruto-doujins-5761"),
                Pair("Kantai Collection", "/kantai-collection-doujins-22720"),
                Pair("Hentai Game CG-Sets", "/hentai-game-cg-sets-2422"),
                Pair("One Piece", "/one-piece-doujins-6080"),
                Pair("Granblue Fantasy", "/granblue-fantasy-doujins-28177"),
                Pair("Azur Lane", "/azur-lane-doujins-34298"),
                Pair("Sword Art Online", "/sword-art-online-doujins-7246"),
                Pair("Idolmaster", "/idolmaster-4281"),
                Pair("My Hero Academia", "/my-hero-academia-doujins-28744"),
                Pair("Love Live", "/love-live-doujins-21865"),
                Pair("Pokemon", "/pokemon-doujins-6393"),
                Pair("Dragon Ball", "/dragon-ball-doujins-1238"),
                Pair("CGs - Mixed Series", "/cgs-mixed-series-35311"),
                Pair("Doujins - Mixed Series", "/doujins-mixed-series-20091"),
                Pair("Hentai Magazine Chapters", "/hentai-magazine-chapters-2766"),
                Pair("Hentai Magazine Chapters - Super-Shorts", "/hentai-magazine-chapters-super-shorts-19933"),
                Pair("Hentai Manga", "/hentai-manga-19"),
            ),
        )

    private class SortFilter :
        UriPartFilter(
            "Sort",
            arrayOf(
                Pair("Newest First", ""),
                Pair("Oldest First", "created_at"),
                Pair("Alphabetical", "name"),
                Pair("Rating", "-cached_score"),
                Pair("Popularity", "-cached_views"),
            ),
        )

    private class PopularityPeriodFilter :
        UriPartFilter(
            "Period",
            arrayOf(
                Pair("This Month", "/top"),
                Pair("This Year", "/top/year"),
                Pair("All Time", "/top/all"),
            ),
        )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private inline fun <reified T> Iterable<*>.findInstance() = find { it is T } as? T

    companion object {
        private const val PAGE_DAYS = 3
    }
}

@Serializable
data class DoujinsLatestResponse(
    val folders: List<DoujinsFolder>,
)

@Serializable
data class DoujinsFolder(
    val link: String,
    val name: String,
    val artistList: String,
    val tags: List<DoujinsTag>,
    val thumbnail2: String,
)

@Serializable
data class DoujinsTag(
    val tag: String,
)
