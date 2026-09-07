package reikai.novel.font

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The rules that decide whether a font is usable at all. They run before anything is written, so a
 * download that reports success is always a font the picker will go on to offer.
 */
class NovelFontTest {

    @Test
    fun `ttf and otf are the formats a font can arrive in`() {
        isSupportedFontFile("Merriweather.ttf") shouldBe true
        isSupportedFontFile("Merriweather.OTF") shouldBe true
    }

    /**
     * The whole reason the download filters rather than falling back to whatever came first: Android
     * cannot load either, so one would install and then never appear.
     */
    @Test
    fun `woff and woff2 are not`() {
        isSupportedFontFile("Merriweather.woff") shouldBe false
        isSupportedFontFile("Merriweather.woff2") shouldBe false
    }

    @Test
    fun `a stylesheet offering a usable face gives its url`() {
        val css = """
            @font-face { font-family: 'Lora'; src: url(https://fonts.gstatic.com/s/lora/v35/abc.ttf) format('truetype'); }
        """.trimIndent()

        firstSupportedFontUrl(css) shouldBe "https://fonts.gstatic.com/s/lora/v35/abc.ttf"
    }

    /** What a modern browser agent is answered with, and what makes the download fail out loud. */
    @Test
    fun `a stylesheet offering only woff2 gives nothing`() {
        val css = """
            @font-face { font-family: 'Lora'; src: url(https://fonts.gstatic.com/s/lora/v35/abc.woff2) format('woff2'); }
        """.trimIndent()

        firstSupportedFontUrl(css) shouldBe null
    }

    @Test
    fun `a usable face is taken even when a woff2 one comes first`() {
        val css = """
            src: url(https://fonts.gstatic.com/s/lora/v35/a.woff2) format('woff2');
            src: url(https://fonts.gstatic.com/s/lora/v35/b.ttf) format('truetype');
        """.trimIndent()

        firstSupportedFontUrl(css) shouldBe "https://fonts.gstatic.com/s/lora/v35/b.ttf"
    }

    @Test
    fun `a truetype file is recognised by its header`() {
        isSfntHeader(byteArrayOf(0x00, 0x01, 0x00, 0x00)) shouldBe true
    }

    @Test
    fun `an opentype file is recognised by its header`() {
        isSfntHeader("OTTO".toByteArray()) shouldBe true
    }

    /** A woff2 file renamed to .ttf, which is what the name check alone would let through. */
    @Test
    fun `a woff2 file is refused by its header whatever it is called`() {
        isSfntHeader("wOF2".toByteArray()) shouldBe false
    }

    @Test
    fun `a file too short to have a header is refused`() {
        isSfntHeader(byteArrayOf(0x00, 0x01)) shouldBe false
    }

    @Test
    fun `a file name becomes a readable label`() {
        fontDisplayName("Noto_Serif-Regular.ttf") shouldBe "Noto Serif Regular"
    }
}
