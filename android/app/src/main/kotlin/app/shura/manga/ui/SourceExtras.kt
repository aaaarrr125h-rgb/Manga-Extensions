package app.shura.manga.ui

import app.shura.manga.ShuraRepository
import app.shura.source.host.InstalledSource

/**
 * The extras the UI passes between screens.
 *
 * Only stable, serialisable handles cross an Intent: the package name and source id identify the
 * source, and a manga/chapter reference is the opaque string the source itself handed out. A
 * loaded extension is never put in an Intent, because it lives in a classloader that must stay in
 * this process.
 */
internal object SourceExtras {
    const val PACKAGE = "app.shura.manga.ui.source_package"
    const val SOURCE_ID = "app.shura.manga.ui.source_id"
    const val MANGA_REF = "app.shura.manga.ui.manga_ref"
    const val MANGA_TITLE = "app.shura.manga.ui.manga_title"
    const val CHAPTER_REF = "app.shura.manga.ui.chapter_ref"
    const val CHAPTER_NAME = "app.shura.manga.ui.chapter_name"
}

/**
 * Re-opens the installed extensions and finds the source the caller navigated from.
 *
 * The catalogue is built fresh per screen rather than cached in a global, so a screen never holds
 * a classloader open after it is gone. It is a little wasteful and it is what keeps the extension
 * lifetime tied to the screen that is actually using it.
 */
internal suspend fun ShuraRepository.findSource(packageName: String, sourceId: Long): InstalledSource? =
    repository.catalogue().firstOrNull {
        it.packageName == packageName && it.descriptor.sourceId == sourceId
    }
