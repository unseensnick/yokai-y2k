package reikai.novel.content

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

/**
 * The one codec for the stored rule list, used by the editor and by the reader.
 *
 * Unknown keys are ignored so a list written by a newer build still reads on an older one. A strict
 * decode throws there, which surfaces as an empty list, and the next save writes that emptiness back
 * over every rule the user had.
 */
val novelRegexRuleJson: Json = Json { ignoreUnknownKeys = true }
