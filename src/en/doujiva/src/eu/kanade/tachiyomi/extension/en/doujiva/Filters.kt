package eu.kanade.tachiyomi.extension.en.doujiva

import eu.kanade.tachiyomi.source.model.Filter

open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
    fun toUriPart() = vals[state].second
}

// Ignored whenever a text search query is present - the /search endpoint doesn't
// take sort/mediaType params, only the browse listing (/) does.
class SortFilter :
    UriPartFilter(
        "Sort (ignored when searching)",
        arrayOf(
            "Latest" to "",
            "Popular Today" to "popular-today",
            "Popular This Week" to "popular-week",
            "Popular All Time" to "popular-all",
        ),
    )

class MediaTypeFilter :
    UriPartFilter(
        "Media Type (ignored when searching)",
        arrayOf(
            "All" to "",
            "Manga / Doujinshi" to "MANGA",
            "Manhwa" to "MANHWA",
            "Western" to "WESTERN",
        ),
    )
