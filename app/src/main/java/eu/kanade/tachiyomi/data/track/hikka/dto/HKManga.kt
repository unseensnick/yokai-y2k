package eu.kanade.tachiyomi.data.track.hikka.dto

import eu.kanade.tachiyomi.data.track.hikka.HikkaApi
import eu.kanade.tachiyomi.data.track.hikka.stringToNumber
import eu.kanade.tachiyomi.data.track.hikka.toTrackStatus
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Locale

@Serializable
data class HKManga(
    @SerialName("data_type")
    val dataType: String,
    @SerialName("title_original")
    val titleOriginal: String,
    @SerialName("media_type")
    val mediaType: String?,
    @SerialName("title_ua")
    val titleUa: String? = null,
    @SerialName("title_en")
    val titleEn: String? = null,
    val chapters: Int? = null,
    val volumes: Int? = null,
    @SerialName("translated_ua")
    val translatedUa: Boolean,
    val status: String,
    val image: String,
    val year: Int? = null,
    @SerialName("scored_by")
    val scoredBy: Int,
    val score: Double,
    val slug: String,
    @SerialName("start_date")
    val startDate: Long? = null,
    val read: List<HKRead>? = emptyList(),
    // RK: richer fields the /manga/{slug} endpoint returns, used by "Fill from tracker". The bind path
    // (toTrack) ignores them; only getMangaMetadata reads them.
    @SerialName("synopsis_ua")
    val synopsisUa: String? = null,
    @SerialName("synopsis_en")
    val synopsisEn: String? = null,
    val authors: List<HKAuthor> = emptyList(),
    val genres: List<HKGenre> = emptyList(),
) {
    // RK: contentType ("manga"/"novel") keeps a novel bind on the /novel content tree; defaults to
    // manga so the existing manga callers are unchanged.
    fun toTrack(trackerId: Long, contentType: String = "manga"): TrackSearch {
        return TrackSearch.create(trackerId).apply {
            remote_id = stringToNumber(this@HKManga.slug)
            title = this@HKManga.titleUa ?: this@HKManga.titleEn ?: this@HKManga.titleOriginal
            total_chapters = this@HKManga.chapters?.toLong() ?: 0
            cover_url = this@HKManga.image
            score = this@HKManga.score
            tracking_url = "${HikkaApi.BASE_URL}/$contentType/${this@HKManga.slug}"
            publishing_status = this@HKManga.status
            publishing_type = this@HKManga.mediaType?.replace("_", " ").orEmpty()

            startDate?.takeIf { it != 0L }?.let {
                val outputDf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                start_date = try {
                    outputDf.format(it * 1000)
                } catch (_: Exception) {
                    ""
                }
            }

            val userProgress = read?.firstOrNull()
            if (userProgress != null) {
                status = toTrackStatus(userProgress.status)
                last_chapter_read = userProgress.chapters.toDouble()
                score = userProgress.score.toDouble()
                started_reading_date = (userProgress.startDate ?: 0L) * 1000
                finished_reading_date = (userProgress.endDate ?: 0L) * 1000
            }
        }
    }
}

// RK: Hikka credits people with per-role entries; roles carry `name_en` values like "Story"/"Art"
// (mirrors AniList/MAL), so the metadata mapper splits author vs artist on those.
@Serializable
data class HKAuthor(
    val person: HKPerson? = null,
    val roles: List<HKRole> = emptyList(),
)

@Serializable
data class HKPerson(
    @SerialName("name_native")
    val nameNative: String? = null,
    @SerialName("name_ua")
    val nameUa: String? = null,
    @SerialName("name_en")
    val nameEn: String? = null,
)

@Serializable
data class HKRole(
    val slug: String? = null,
    @SerialName("name_en")
    val nameEn: String? = null,
    @SerialName("name_ua")
    val nameUa: String? = null,
)

@Serializable
data class HKGenre(
    @SerialName("name_ua")
    val nameUa: String? = null,
    @SerialName("name_en")
    val nameEn: String? = null,
    val slug: String? = null,
    val type: String? = null,
)
