package mihon.core.migration.migrations

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import mihon.core.migration.MigrationContext
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import reikai.domain.novel.DEAD_READER_PADDING_KEY
import reikai.domain.novel.NovelPreferences
import reikai.presentation.recents.EmittingPreferenceStore

class SplitNovelReaderPaddingMigrationTest {

    private val store = EmittingPreferenceStore()
    private val novelPreferences = NovelPreferences(store)
    private val migration = SplitNovelReaderPaddingMigration(store, novelPreferences)

    private fun margins() = with(novelPreferences) {
        listOf(readerMarginTop(), readerMarginBottom(), readerMarginLeft(), readerMarginRight()).map { it.get() }
    }

    @Test
    @DisplayName("a customised padding becomes all four margins")
    fun customisedPaddingSplits() = runTest {
        store.getInt(DEAD_READER_PADDING_KEY, 0).set(40)

        migration.invoke(MigrationContext(dryrun = false, previousVersion = 190))

        margins() shouldBe listOf(40, 40, 40, 40)
    }

    @Test
    @DisplayName("the retired padding key is cleared once it has been carried over")
    fun retiredKeyIsCleared() = runTest {
        store.getInt(DEAD_READER_PADDING_KEY, 0).set(40)

        migration.invoke(MigrationContext(dryrun = false, previousVersion = 190))

        store.getInt(DEAD_READER_PADDING_KEY, 0).isSet() shouldBe false
    }

    @Test
    @DisplayName("an untouched padding leaves the margins at their own defaults")
    fun untouchedPaddingWritesNothing() = runTest {
        migration.invoke(MigrationContext(dryrun = false, previousVersion = 190))

        novelPreferences.readerMarginTop().isSet() shouldBe false
    }

    @Test
    @DisplayName("a fresh install carries nothing over")
    fun freshInstallDoesNothing() = runTest {
        store.getInt(DEAD_READER_PADDING_KEY, 0).set(40)

        migration.invoke(MigrationContext(dryrun = false, previousVersion = 0))

        novelPreferences.readerMarginTop().isSet() shouldBe false
    }
}
