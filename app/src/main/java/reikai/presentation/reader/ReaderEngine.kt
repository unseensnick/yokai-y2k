package reikai.presentation.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import reikai.domain.reader.ChapterProgress

/**
 * The Reikai-owned reader engine, above one provider per content type. It owns dialog dispatch and
 * the viewport slot. Navigation and position stay with the provider for now, and menu visibility
 * belongs to the host, because half of it is the insets controller.
 */
@AssistedInject
class ReaderEngine(
    // Assisted: the provider wraps a model the host has already resolved, so it can only be built at
    // the call site. Public because the host builds its viewport through it, which is what keeps the
    // host from branching on content type once there is a second provider.
    @Assisted val provider: ReaderProvider,
) : ViewModel() {

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(provider: ReaderProvider): ReaderEngine
    }

    // Session state, shared here rather than in the provider: this engine outlives an Activity
    // recreation, so state kept alive by the Activity's scope would freeze the first time the reader
    // is rotated. Eager, because the window's orientation and keep-screen-on follow these whether or
    // not anything is composed.

    /** What the chrome shows, answered by whichever content type this session is for. */
    val chrome: StateFlow<ReaderChromeState> =
        provider.chrome.stateIn(viewModelScope, SharingStarted.Eagerly, ReaderChromeState())

    /** The bottom-bar buttons this session offers, likewise its own rather than manga's. */
    val bottomButtons: StateFlow<Set<String>> =
        provider.bottomButtons.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Where the reader is in the chapter, and which navigator shows it. */
    val navigator: StateFlow<ReaderNavigatorState> =
        provider.navigator.stateIn(viewModelScope, SharingStarted.Eagerly, ReaderNavigatorState())

    /** Scrubbing goes to the viewport rather than the provider, since only it can move the reader. */
    fun seek(progress: ChapterProgress) {
        viewport.value?.seekTo(progress)
    }

    // The step and the viewer's response to it are sequenced here, because the provider knows which
    // chapter is next and only the viewport can act on having arrived.

    /** The chapter sheet's rows and verbs, for whichever content type this session is. */
    val chapterList: ReaderChapterList get() = provider.chapterList

    /** Typography, or null where this session's pages are images. */
    val textSettings: ReaderTextSettings? get() = provider.textSettings

    fun previousChapter() = stepChapter { provider.previousChapter() }

    fun nextChapter() = stepChapter { provider.nextChapter() }

    private fun stepChapter(step: suspend () -> Unit) {
        viewModelScope.launch {
            step()
            viewport.value?.onChapterStepped()
        }
    }

    // The bar's own verbs, so a button acts on the entry that is open rather than on a manga model
    // the session may not have.

    val orientation: StateFlow<Int> =
        provider.orientation.stateIn(viewModelScope, SharingStarted.Eagerly, ReaderOrientation.DEFAULT.flagValue)

    fun setOrientation(flagValue: Int) = provider.setOrientation(flagValue)

    val keepScreenOn: StateFlow<Boolean> =
        provider.keepScreenOn.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setKeepScreenOn(enabled: Boolean) = provider.setKeepScreenOn(enabled)

    // Dialogs: one slot, so raising a dialog is also how the previous one closes.

    private val mutableDialog = MutableStateFlow<ReaderDialog?>(null)
    val dialog: StateFlow<ReaderDialog?> = mutableDialog.asStateFlow()

    fun openDialog(dialog: ReaderDialog) {
        mutableDialog.value = dialog
    }

    fun dismissDialog() {
        mutableDialog.value = null
    }

    // Viewport: what is currently rendering the entry, whatever content type it is.

    private val mutableViewport = MutableStateFlow<ReaderViewport?>(null)
    val viewport: StateFlow<ReaderViewport?> = mutableViewport.asStateFlow()

    /**
     * Swaps in the viewport the host just built. Destroying the outgoing one happens here rather
     * than at the call site, because losing that step leaks the whole previous view tree and nothing
     * would fail loudly.
     *
     * Building is separate because only the host can supply itself to a viewer constructor, and
     * folding that in here would mean nothing could exercise the swap without inventing a host.
     */
    fun installViewport(viewport: ReaderViewport) {
        mutableViewport.value?.destroy()
        mutableViewport.value = viewport
    }

    /** Called when the host goes away, since the viewport holds its view tree. */
    fun destroyViewport() {
        mutableViewport.value?.destroy()
        mutableViewport.value = null
    }
}
