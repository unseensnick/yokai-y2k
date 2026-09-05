package reikai.domain.novel

/**
 * Which reader a novel opens in. Persisted by name through `getEnum`, so the constant names are
 * load-bearing.
 *
 * [LEGACY] is the standalone novel reader that predates the shared host. It stays the default while
 * the host is built out, so a half-finished reader is never what a user lands in, and it goes once
 * the host reaches parity.
 */
enum class NovelRenderingMode {
    LEGACY,

    /** The shared reader host, rendering the same HTML pipeline the legacy reader uses. */
    WEBVIEW,
}
