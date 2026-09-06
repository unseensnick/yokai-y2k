package mihon.core.migration.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import logcat.LogPriority
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import reikai.domain.novel.DEAD_READER_PADDING_KEY
import reikai.domain.novel.NovelPreferences
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat

/**
 * Carries a customised novel-reader page padding into the four margins that replaced it.
 *
 * The old single value set all four edges, so copying it to each keeps the page looking exactly as it
 * did. A reader who never moved the slider has nothing stored and keeps the new defaults, which are
 * the same number anyway.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class SplitNovelReaderPaddingMigration(
    private val preferenceStore: PreferenceStore,
    private val novelPreferences: NovelPreferences,
) : Migration {
    // RK: fires once when the shipped versionCode crosses 191, the version the four margins ship in.
    override val version: Float = 191f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        if (migrationContext.previousVersion == 0) return@withIOContext true // fresh install: nothing stored

        runCatching {
            // An untouched slider stored nothing, so it is left to the new defaults rather than
            // being written back as an explicit value.
            val padding = preferenceStore.getInt(DEAD_READER_PADDING_KEY, 0)
            if (!padding.isSet()) return@runCatching

            val stored = padding.get()
            novelPreferences.readerMarginTop().set(stored)
            novelPreferences.readerMarginBottom().set(stored)
            novelPreferences.readerMarginLeft().set(stored)
            novelPreferences.readerMarginRight().set(stored)
            padding.delete()
        }.onFailure {
            logcat(LogPriority.ERROR, it) { "Failed to split the novel reader padding into margins" }
        }

        return@withIOContext true
    }
}
