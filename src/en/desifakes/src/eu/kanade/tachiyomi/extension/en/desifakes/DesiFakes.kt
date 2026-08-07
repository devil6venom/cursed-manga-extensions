package eu.kanade.tachiyomi.extension.en.desifakes

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
import kotlinx.serialization.json.JsonElement
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.time.Instant

// Blog-style WordPress gallery site (wp-theme-virality). There is no separate
// "popular" listing, so the home/archive feed doubles as both - see supportsLatest.
@Source
abstract class DesiFakes : KeiSource() {

    override val supportsLatest = false

    // Popular

    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = client.get(listingUrl(page = page)).asJsoup()
        return parseMangaList(document)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    // Search

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = if (query.isNotBlank()) {
            searchUrl(query, page)
        } else {
            val category = filters.firstInstanceOrNull<CategoryFilter>()?.toUriPart().orEmpty()
            listingUrl(page = page, category = category)
        }

        val document = client.get(url).asJsoup()
        return parseMangaList(document)
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(CategoryFilter())

    private fun listingUrl(page: Int, category: String = ""): String {
        val categoryPath = if (category.isBlank()) "" else "/$category"
        return if (page > 1) "$baseUrl$categoryPath/page/$page/" else "$baseUrl$categoryPath/"
    }

    private fun searchUrl(query: String, page: Int): String {
        val pagePath = if (page > 1) "/page/$page/" else "/"
        return "$baseUrl$pagePath?s=$query"
    }

    private fun parseMangaList(document: Document): MangasPage {
        val mangas = document.select("article").map { it.toSManga() }
        val hasNextPage = document.selectFirst("a.next.page-numbers") != null

        return MangasPage(mangas, hasNextPage)
    }

    private fun Element.toSManga(): SManga {
        val titleLink = selectFirst(".entry-title a")!!
        val thumbnail = selectFirst(".imgthumb img")

        return SManga.create().apply {
            title = titleLink.text()
            setUrlWithoutDomain(titleLink.absUrl("href"))
            thumbnail_url = thumbnail?.attr("abs:data-lazy-src")
                ?.ifEmpty { thumbnail.attr("abs:src") }
        }
    }

    // Details + Chapters
    // Each post is a single photo gallery, so it's mapped to one generic chapter.

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(baseUrl + manga.url).asJsoup()

        val updatedManga = if (fetchDetails) {
            manga.apply {
                description = document.select(".entry-content > p")
                    .firstOrNull { it.text().isNotBlank() }
                    ?.text()
                genre = document.select(".entry-content a[rel=tag]").joinToString { it.text() }
                update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
            }
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            val dateUpload = document.selectFirst("meta[property=article:published_time]")
                ?.attr("content")
                ?.let { Instant.parseOrNull(it)?.toEpochMilliseconds() }
                ?: 0L

            listOf(
                SChapter.create().apply {
                    url = manga.url
                    name = "Gallery"
                    date_upload = dateUpload
                },
            )
        } else {
            chapters
        }

        return SMangaUpdate(manga = updatedManga, chapters = updatedChapters)
    }

    // Pages
    // Every gallery image sits inside an <a href> pointing at the full-size original,
    // wrapping an <img> whose own src/srcset are lazy-loaded and often unreliable.

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(baseUrl + chapter.url).asJsoup()

        return document.select(".entry-content a:has(img)").mapIndexed { index, element ->
            Page(index, imageUrl = element.absUrl("href"))
        }
    }
}
