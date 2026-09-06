package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.novel.NovelPreferences
import reikai.presentation.novel.reader.NovelReaderSettings
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.domain.manga.model.Manga

/**
 * The orientation rule, pinned once for both content types: the per-entry rotation flag crosses the
 * provider seam **unresolved**, so 0 keeps meaning "follow this type's default" rather than being
 * replaced by the default's value. Resolving it would stop the picker's "use default" row showing as
 * selected, for one type only. Each probe pins its adapter's answer, not a model that resolves before
 * the seam; that gap and why it was declined are in docs/dev/plans/content-layer-reader-surface.md.
 */
class ReaderProviderConformanceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `an entry pinned to an orientation reports that orientation`(probe: ReaderOrientationProbe) = runTest {
        val provider = probe.provider(
            stored = ReaderOrientation.LOCKED_LANDSCAPE.flagValue,
            default = ReaderOrientation.PORTRAIT.flagValue,
        )

        provider.orientation.first() shouldBe ReaderOrientation.LOCKED_LANDSCAPE.flagValue
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `an entry following the default reports zero rather than the default`(probe: ReaderOrientationProbe) =
        runTest {
            val provider = probe.provider(
                stored = ReaderOrientation.DEFAULT.flagValue,
                default = ReaderOrientation.PORTRAIT.flagValue,
            )

            provider.orientation.first() shouldBe ReaderOrientation.DEFAULT.flagValue
        }

    companion object {
        @JvmStatic
        fun probes() = listOf(MangaOrientationProbe(), NovelOrientationProbe())
    }
}

/** One content type's provider, built so that its stored flag and its default are told apart. */
interface ReaderOrientationProbe {

    /**
     * [stored] is the entry's own flag, [default] the value this type would resolve a 0 to. They are
     * always different, so a provider that resolved would answer [default] and fail.
     */
    fun provider(stored: Int, default: Int): ReaderProvider
}

class MangaOrientationProbe : ReaderOrientationProbe {

    override fun toString() = "manga"

    override fun provider(stored: Int, default: Int): ReaderProvider {
        val manga = Manga.create().copy(id = 1L, source = 1L, url = "/1", viewerFlags = stored.toLong())
        return MangaReaderProvider(
            viewModel = mockk(relaxed = true) {
                every { state } returns MutableStateFlow(ReaderViewModel.State(manga = manga))
            },
            readerPreferences = ReaderPreferences(
                InMemoryPreferenceStore(
                    sequenceOf(InMemoryPreference("pref_default_orientation_type_key", default, default)),
                ),
            ),
            downloadManager = mockk(relaxed = true),
        )
    }
}

class NovelOrientationProbe : ReaderOrientationProbe {

    override fun toString() = "novel"

    // The settings object is stubbed rather than built: it carries 28 fields and only these two decide
    // the rule, so a mock states which one the provider is required to read.
    override fun provider(stored: Int, default: Int): ReaderProvider =
        NovelReaderProvider(
            viewModel = mockk(relaxed = true) {
                every { settings } returns MutableStateFlow(
                    mockk<NovelReaderSettings>(relaxed = true) {
                        every { orientation } returns stored
                        every { resolvedOrientation } returns default
                    },
                )
            },
            novelPreferences = NovelPreferences(InMemoryPreferenceStore()),
        )
}
