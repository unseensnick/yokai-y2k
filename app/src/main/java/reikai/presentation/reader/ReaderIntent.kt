package reikai.presentation.reader

import android.content.Intent
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType

/**
 * How the reader host names the entry it was asked to open: a type tag beside a raw id, never a bare
 * `Long`. The two id spaces overlap, so a novel id read as a manga id would open the wrong entry
 * instead of failing, and an Intent extra cannot carry the sealed type itself.
 *
 * The manga path keeps writing upstream's `"manga"` extra as well, since `ReaderViewModel` reads that
 * out of its own `SavedStateHandle`; this tag sits beside it rather than replacing it.
 */
private const val EXTRA_ENTRY_TYPE = "entry_type"
private const val EXTRA_ENTRY_ID = "entry_id"

fun Intent.putEntryId(entryId: EntryId): Intent = apply {
    putExtra(EXTRA_ENTRY_TYPE, entryId.contentType.name)
    putExtra(EXTRA_ENTRY_ID, entryId.rawId)
}

/** Null when the tag is absent or unusable, which the host treats as a launch it cannot serve. */
fun Intent.readEntryId(): EntryId? =
    entryIdOf(getStringExtra(EXTRA_ENTRY_TYPE), getLongExtra(EXTRA_ENTRY_ID, NO_ID))

/** The sentinel upstream already uses for an absent id, kept so both halves read the same. */
internal const val NO_ID = -1L

/**
 * The decision itself, kept out of [Intent] so it can be tested without the framework. [ContentType]
 * is persisted by name elsewhere, so matching on the name is the same contract the store relies on.
 */
internal fun entryIdOf(typeName: String?, rawId: Long): EntryId? {
    if (rawId == NO_ID) return null
    return when (typeName) {
        ContentType.MANGA.name -> EntryId.Manga(rawId)
        ContentType.NOVELS.name -> EntryId.Novel(rawId)
        // ALL is a filter, never an entry, and an unknown tag is a newer build's intent.
        else -> null
    }
}
