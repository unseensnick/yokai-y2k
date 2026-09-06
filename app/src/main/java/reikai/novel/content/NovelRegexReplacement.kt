package reikai.novel.content

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * One user-defined find/replace rule applied to chapter text before it renders. Persisted as JSON in
 * [reikai.domain.novel.NovelPreferences.readerRegexReplacements], so the property names are the
 * stored schema: renaming one silently drops that field from every saved rule.
 */
@Serializable
data class NovelRegexReplacement(
    val title: String,
    val pattern: String,
    val replacement: String,
    val enabled: Boolean = true,
    val isRegex: Boolean = true,
    val matchWholeWord: Boolean = false,
    val caseSensitive: Boolean = false,
    val id: String = UUID.randomUUID().toString(),
)
