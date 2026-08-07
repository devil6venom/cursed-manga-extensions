package eu.kanade.tachiyomi.extension.en.hentaipaw

import eu.kanade.tachiyomi.source.model.Page
import kotlinx.serialization.Serializable

@Serializable
class ViewerDto(
    private val slides: List<SlideDto>,
) {
    fun toPageList(): List<Page> = slides.mapIndexed { index, slide -> Page(index, imageUrl = slide.imageUrl()) }
}

@Serializable
class SlideDto(
    private val src: String,
) {
    fun imageUrl() = src
}
