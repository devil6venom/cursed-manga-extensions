package eu.kanade.tachiyomi.extension.en.desifakes

import eu.kanade.tachiyomi.source.model.Filter

open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
    fun toUriPart() = vals[state].second
}

class CategoryFilter :
    UriPartFilter(
        "Category",
        arrayOf(
            "All" to "",
            "XXX Photos" to "xxx-photos",
            "Nude Bhabhi" to "nude-bhabhi",
            "Hollywood Actress" to "hollywood-actress",
            "Bollywood Actress" to "bollywood-actress",
        ),
    )
