package eu.kanade.tachiyomi.extension.en.doujiva

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.Serializable

// Parsed from the <script type="application/ld+json"> "CreativeWork" block on a manga's
// detail page. This is plain, unescaped JSON (unlike the rest of the page, which is React
// Server Component flight data) so it's used instead of RSC extraction for detail parsing.
@Serializable
class MangaDetailDto(
    private val name: String,
    private val alternateName: String? = null,
    private val image: String? = null,
    private val author: List<AuthorDto> = emptyList(),
    private val genre: List<String> = emptyList(),
    private val numberOfPages: Int = 0,
) {
    val pageCount: Int get() = numberOfPages

    fun updateSManga(manga: SManga) = manga.apply {
        title = name
        thumbnail_url = image ?: thumbnail_url
        author = this@MangaDetailDto.author.joinToString().ifBlank { null }
        genre = this@MangaDetailDto.genre.joinToString().ifBlank { null }
        description = alternateName?.takeIf { it.isNotBlank() && it != name }
            ?.let { "Alternate title: $it" }
        status = SManga.COMPLETED
    }
}

@Serializable
class AuthorDto(val name: String)
