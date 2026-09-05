package reikai.presentation.reader

import androidx.lifecycle.ViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
