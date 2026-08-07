package eu.kanade.tachiyomi.extension.en.hentaione

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
import keiyoushi.utils.extractNextJs
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class HentaiOne : KeiSource() {

    // Popular

    override suspend fun getPopularManga(page: Int): MangasPage = mangaListRequest("$baseUrl/articles/rank?page=$page".toHttpUrl())

    // Latest

    override suspend fun getLatestUpdates(page: Int): MangasPage = mangaListRequest("$baseUrl/?page=$page".toHttpUrl())

    // Search

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/articles/search".toHttpUrl().newBuilder()
            .addQueryParameter("keyword", query)
            .addQueryParameter("page", page.toString())
            .build()

        return mangaListRequest(url)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.getOrNull(0) != "articles") return null
        val id = url.pathSegments.getOrNull(1) ?: return null

        val document = client.get("$baseUrl/articles/$id").asJsoup()
        return mangaDetailsParse(document).apply { this.url = "/articles/$id" }
    }

    private suspend fun mangaListRequest(url: HttpUrl): MangasPage {
        val document = client.get(url).asJsoup()
        return mangaListParse(document)
    }

    private fun mangaListParse(document: Document): MangasPage {
        val container = document.selectFirst("div:has(> h1:contains(New Hentai Manga))")
            ?: document.selectFirst("div:has(> h1:contains(Search Results))")
            ?: document.selectFirst("div:has(> h1:contains(Daily Ranking))")
            ?: document.selectFirst("main")
            ?: document

        val mangas = container.select("a.group[href^=/articles/]").map { it.toSManga() }
        val hasNextPage = document.select("nav[aria-label=pagination] a[aria-label='next page']").isNotEmpty()

        return MangasPage(mangas, hasNextPage)
    }

    private fun Element.toSManga() = SManga.create().apply {
        setUrlWithoutDomain(absUrl("href"))
        val titleElement = selectFirst("div.line-clamp-2")
        title = titleElement?.attr("title")?.ifBlank { titleElement.text() } ?: titleElement?.text().orEmpty()
        thumbnail_url = selectFirst("img")?.attr("abs:src")
    }

    // Details & chapters
    // This is a one-shot gallery site: each article has a single "chapter" that is the
    // article page itself. The full-resolution page images live on a separate /viewer
    // route (see getPageList) - the article page's own thumbnail grid is low-res only.

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(baseUrl + manga.url).asJsoup()

        val updatedManga = if (fetchDetails) mangaDetailsParse(document).apply { url = manga.url } else manga
        val updatedChapters = if (fetchChapters) chapterListParse(manga.url) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        val tagGroups = document.select("#article-tag-information > div > div").associate { group ->
            val label = group.selectFirst("h3")?.text().orEmpty().removeSuffix(":")
            val values = group.select("a > div").eachText().ifEmpty { group.select("p").eachText() }

            label to values
        }

        title = document.selectFirst("#article-details h1")?.text().orEmpty()
        thumbnail_url = document.selectFirst("#article-details img")?.attr("abs:src")
        author = buildList {
            addAll(tagGroups["Artists"].orEmpty())
            addAll(tagGroups["Groups"].orEmpty())
        }.filterNot { it == "N/A" }.joinToString()
        genre = buildList {
            addAll(tagGroups["Tags"].orEmpty())
            addAll(tagGroups["Parodies"].orEmpty())
            addAll(tagGroups["Characters"].orEmpty())
            addAll(tagGroups["Category"].orEmpty())
        }.filterNot { it == "N/A" }.joinToString()
        status = SManga.COMPLETED
    }

    private fun chapterListParse(mangaUrl: String): List<SChapter> = listOf(
        SChapter.create().apply {
            url = mangaUrl
            name = "Chapter"
        },
    )

    // Pages
    // The article page only exposes low-res preview thumbnails; the actual full-resolution
    // pages are hydrated as RSC data on the /viewer route, keyed by articleId.

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val id = chapter.url.substringAfterLast("/")
        val document = client.get("$baseUrl/viewer?articleId=$id&page=1").asJsoup()

        val viewer = document.extractNextJs<ViewerDto> { element ->
            element is JsonObject && "slides" in element && "articleId" in element
        } ?: throw Exception("Unable to find page list")

        return viewer.toPageList()
    }
}
