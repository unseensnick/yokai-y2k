package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType

/**
 * The Intent halves are a thin shim over [entryIdOf], which is where the decision that matters lives:
 * a wrong answer here opens the wrong entry rather than failing, because a manga and a novel can hold
 * the same row id.
 */
class ReaderIntentTest {

    @Test
    fun `a manga tag reads back as a manga id`() {
        entryIdOf(ContentType.MANGA.name, rawId = 7L) shouldBe EntryId.Manga(7L)
    }

    @Test
    fun `a novel tag reads back as a novel id`() {
        entryIdOf(ContentType.NOVELS.name, rawId = 7L) shouldBe EntryId.Novel(7L)
    }

    /** The same raw id under the two tags must not produce the same entry. */
    @Test
    fun `the tag is what separates the two id spaces`() {
        val manga = entryIdOf(ContentType.MANGA.name, rawId = 42L)
        val novel = entryIdOf(ContentType.NOVELS.name, rawId = 42L)

        (manga == novel) shouldBe false
    }

    @Test
    fun `a missing id is not an entry`() {
        entryIdOf(ContentType.MANGA.name, rawId = NO_ID) shouldBe null
    }

    @Test
    fun `a missing tag is not an entry`() {
        entryIdOf(typeName = null, rawId = 7L) shouldBe null
    }

    /** ALL is a library filter, so a launch carrying it names no single entry to open. */
    @Test
    fun `the all filter is not an entry`() {
        entryIdOf(ContentType.ALL.name, rawId = 7L) shouldBe null
    }

    /** An intent written by a newer build must fail closed rather than guess a type. */
    @Test
    fun `an unknown tag is not an entry`() {
        entryIdOf("AUDIOBOOKS", rawId = 7L) shouldBe null
    }
}
