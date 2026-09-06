package reikai.domain.novel

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/**
 * Which reader a novel opens in. Persisted by name through `getEnum`, so the constant names are
 * load-bearing; an unknown one falls back to the default, so retiring a constant needs no migration.
 *
 * [LEGACY] is the standalone reader that predates the shared host. It stays the default while the
 * host is built out, so a half-finished reader is never what a user lands in, and goes at cutover.
 */
enum class NovelRenderingMode(val titleRes: StringResource) {
    LEGACY(MR.strings.pref_novel_rendering_mode_legacy),

    /** The shared reader host, rendering the same HTML pipeline the legacy reader uses. */
    WEBVIEW(MR.strings.pref_novel_rendering_mode_webview),

    /** The shared host with the native text renderer, which is what replaces the WebView engine. */
    NATIVE(MR.strings.pref_novel_rendering_mode_native),
}
