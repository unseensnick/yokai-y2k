package reikai.novel.content

import kotlinx.serialization.json.Json
import logcat.LogPriority
import reikai.domain.novel.NovelPreferences
import tachiyomi.core.common.util.system.logcat
import java.util.Collections
import java.util.LinkedHashMap

object NovelRegexReplacements {

    // Compiling a rule set costs more than running it and the same set is re-applied to every
    // chapter, so the compiled form is cached against the raw JSON. Access-ordered and capped, so
    // editing rules mid-session cannot grow it without bound.
    private val cache: MutableMap<String, List<Pair<Regex, String>>> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, List<Pair<Regex, String>>>(16, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, List<Pair<Regex, String>>>,
                ) = size > 16
            },
        )

    fun apply(content: String, preferences: NovelPreferences): String {
        val rulesJson = preferences.readerRegexReplacements().get()
        if (rulesJson.isBlank() || rulesJson == "[]") return content

        val compiled = synchronized(cache) {
            cache.computeIfAbsent(rulesJson) { json ->
                try {
                    val rules: List<NovelRegexReplacement> = Json.decodeFromString(json)
                    rules.mapNotNull { rule ->
                        if (!rule.enabled || rule.pattern.isBlank()) return@mapNotNull null
                        try {
                            val options = if (rule.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                            if (rule.isRegex) {
                                Regex(rule.pattern, options) to rule.replacement
                            } else {
                                val escapedPattern = Regex.escape(rule.pattern)
                                val boundedPattern = if (rule.matchWholeWord) {
                                    "(?<![\\p{L}\\p{N}_])(?:$escapedPattern)(?![\\p{L}\\p{N}_])"
                                } else {
                                    escapedPattern
                                }
                                Regex(boundedPattern, options) to rule.replacement
                            }
                        } catch (e: Exception) {
                            logcat(LogPriority.WARN, e) { "Failed to compile regex for '${rule.title}'" }
                            null
                        }
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "Failed to parse regex replacements" }
                    emptyList()
                }
            }
        }

        var result = content
        for ((regex, replacement) in compiled) {
            try {
                result = regex.replace(result, replacement)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Regex replacement failed" }
            }
        }
        return result
    }
}
