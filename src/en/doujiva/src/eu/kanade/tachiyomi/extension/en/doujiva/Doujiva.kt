package eu.kanade.tachiyomi.extension.en.doujiva

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

// Doujiva is a Next.js (App Router) site. Most of its content ships as escaped React
// Server Component "flight" data rather than plain HTML, BUT the manga listing/search
// grids and each manga's JSON-LD block are both plain server-rendered HTML/JSON - so
// this source sticks to Jsoup + JSON-LD instead of RSC extraction, which is simpler
// and avoids ambiguity with the several sidebar "trending" widgets that share the same
// component and shape as the main grid.
@Source
abstract class Doujiva : KeiSource() {

    private val cdnUrl = "https://cdn.doujiva.com"

    // Popular / Latest

    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = client.get(browseUrl(page = page, sort = "popular-today")).asJsoup()
        return parseMangaList(document)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val document = client.get(browseUrl(page = page)).asJsoup()
        return parseMangaList(document)
    }

    // Search

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = if (query.isNotBlank()) {
            "$baseUrl/search?q=$query&page=$page"
        } else {
            val sort = filters.firstInstanceOrNull<SortFilter>()?.toUriPart().orEmpty()
            val mediaType = filters.firstInstanceOrNull<MediaTypeFilter>()?.toUriPart().orEmpty()
            browseUrl(page = page, sort = sort, mediaType = mediaType)
        }

        val document = client.get(url).asJsoup()
        return parseMangaList(document)
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SortFilter(),
        MediaTypeFilter(),
    )

    private fun browseUrl(page: Int, sort: String = "", mediaType: String = ""): String {
        val params = buildList {
            if (sort.isNotBlank()) add("sort=$sort")
            if (mediaType.isNotBlank()) add("mediaType=$mediaType")
            if (page > 1) add("page=$page")
        }
        return if (params.isEmpty()) "$baseUrl/" else "$baseUrl/?${params.joinToString("&")}"
    }

    private fun parseMangaList(document: Document): MangasPage {
        val mangas = document.select(".content-grid a[href^=\"/manga/\"]").map { it.toSManga() }
        val hasNextPage = document.selectFirst("link[rel=next]") != null

        return MangasPage(mangas, hasNextPage)
    }

    private fun Element.toSManga(): SManga {
        val slug = attr("href").substringAfterLast("/")

        return SManga.create().apply {
            title = selectFirst("h3")?.text().orEmpty()
            setUrlWithoutDomain(attr("href"))
            thumbnail_url = selectFirst("img")?.attr("abs:src")
                ?: "$cdnUrl/$slug/cover.thumb.webp"
        }
    }

    // Details + Chapters
    // Doujiva entries are single-chapter doujinshi/oneshots, so each manga maps to one
    // generic chapter. Detail metadata comes from the page's JSON-LD "CreativeWork" block,
    // which is plain unescaped JSON (unlike the rest of the page).

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(baseUrl + manga.url).asJsoup()
        val detail = document.select("script[type=application/ld+json]")
            .map { it.data() }
            .firstOrNull { "\"@type\":\"CreativeWork\"" in it }
            ?.parseAs<MangaDetailDto>()

        val updatedManga = if (fetchDetails && detail != null) {
            detail.updateSManga(manga).apply {
                update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
            }
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            listOf(
                SChapter.create().apply {
                    url = manga.url
                    name = "Gallery"
                    chapter_number = 1f
                },
            )
        } else {
            chapters
        }

        return SMangaUpdate(manga = updatedManga, chapters = updatedChapters)
    }

    // Pages
    // Page images follow a deterministic, zero-padded CDN path derived from the page count
    // in the same JSON-LD block, so no separate reader-page fetch/parse is needed.

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val slug = chapter.url.substringAfterLast("/")
        val document = client.get(baseUrl + chapter.url).asJsoup()
        val pageCount = document.select("script[type=application/ld+json]")
            .map { it.data() }
            .firstOrNull { "\"@type\":\"CreativeWork\"" in it }
            ?.parseAs<MangaDetailDto>()
            ?.pageCount
            ?: 0

        return (1..pageCount).map { i ->
            val fileName = i.toString().padStart(3, '0')
            Page(i - 1, imageUrl = "$cdnUrl/$slug/chapter-1/$fileName.webp")
        }
    }
}
