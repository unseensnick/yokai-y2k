package reikai.domain.merge

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelChapterAggregation
import reikai.domain.novel.model.NovelChapter

/**
 * The novel side's cross-source chapter identity: two sources' rows share a key when they are the
 * same chapter, which is what the stitch pairs them on before it places the rest by position.
 */
class ChapterMatchKeysTest {

    @Test
    fun `the novel key ignores the chapter-number prefix in a title`() {
        val fromOneSource = NovelChapterAggregation.matchKey("Chapter 12: The Duel", 12.0)
        val fromAnother = NovelChapterAggregation.matchKey("12. The Duel", 12.0)

        fromOneSource shouldBe fromAnother
    }

    @Test
    fun `the novel key falls back to the number for a numeric-only title`() {
        NovelChapterAggregation.matchKey("42", 42.0) shouldBe "n:42.0"
    }

    @Test
    fun `a novel chapter with neither a title nor a number has no identity`() {
        NovelChapterAggregation.matchKey("", 0.0).shouldBeNull()
    }

    @Test
    fun `the value overload matches the row overload`() {
        val name = "Chapter 3: Homecoming"
        val number = 3.0

        NovelChapterAggregation.matchKey(name, number) shouldBe
            NovelChapterAggregation.matchKey(
                NovelChapter(
                    id = 1,
                    novelId = 1,
                    url = "url",
                    name = name,
                    read = false,
                    bookmark = false,
                    lastTextProgress = 0,
                    chapterNumber = number,
                    sourceOrder = 0,
                    dateFetch = 0,
                    dateUpload = 0,
                    page = "",
                ),
            )
    }
}
