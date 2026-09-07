package reikai.presentation.reader.text

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class NovelBionicSpansTest {

    @ParameterizedTest(name = "a {0}-letter word bolds {1}")
    @DisplayName("the bold run follows the vendored bundle's own fixation table")
    @CsvSource(
        "1, 0",
        "2, 1",
        "4, 3",
        "5, 3",
        "12, 10",
        "13, 10",
        "17, 14",
        "18, 14",
        "49, 40",
    )
    fun boldLengthMatchesTheBundle(wordLength: Int, expected: Int) {
        NovelBionicSpans.boldLengthFor(wordLength) shouldBe expected
    }

    @org.junit.jupiter.api.Test
    @DisplayName("a single letter bolds nothing, so one-letter words are not left looking emphasised")
    fun singleLetterBoldsNothing() {
        NovelBionicSpans.boldLengthFor(1) shouldBe 0
    }
}
