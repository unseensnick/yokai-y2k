package eu.kanade.tachiyomi.data.track.mangaupdates

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.DeletableTracker
import eu.kanade.tachiyomi.data.track.mangaupdates.dto.MUListItem
import eu.kanade.tachiyomi.data.track.mangaupdates.dto.MURating
import eu.kanade.tachiyomi.data.track.mangaupdates.dto.namesOfType
import eu.kanade.tachiyomi.data.track.model.TrackMangaMetadata
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.util.lang.htmlDecode
import tachiyomi.i18n.MR
import tachiyomi.domain.track.model.Track as DomainTrack

class MangaUpdates(id: Long) : BaseTracker(id, "MangaUpdates"), DeletableTracker {

    companion object {
        const val READING_LIST = 0L
        const val WISH_LIST = 1L
        const val COMPLETE_LIST = 2L
        const val UNFINISHED_LIST = 3L
        const val ON_HOLD_LIST = 4L

        private val SCORE_LIST = (0..10)
            .flatMap { decimal ->
                when (decimal) {
                    0 -> listOf("-")
                    10 -> listOf("10.0")
                    else -> (0..9).map { fraction ->
                        "$decimal.$fraction"
                    }
                }
            }
    }

    private val interceptor by lazy { MangaUpdatesInterceptor(this) }

    private val api by lazy { MangaUpdatesApi(client, interceptor) }

    override fun getLogo(): Int = R.drawable.brand_mangaupdates

    override fun getStatusList(): List<Long> {
        return listOf(READING_LIST, COMPLETE_LIST, ON_HOLD_LIST, UNFINISHED_LIST, WISH_LIST)
    }

    override fun getStatus(status: Long): StringResource? = when (status) {
        READING_LIST -> MR.strings.reading_list
        WISH_LIST -> MR.strings.wish_list
        COMPLETE_LIST -> MR.strings.complete_list
        ON_HOLD_LIST -> MR.strings.on_hold_list
        UNFINISHED_LIST -> MR.strings.unfinished_list
        else -> null
    }

    override fun getReadingStatus(): Long = READING_LIST

    override fun getRereadingStatus(): Long = -1

    override fun getCompletionStatus(): Long = COMPLETE_LIST

    override fun getScoreList(): List<String> = SCORE_LIST

    override fun indexToScore(index: Int): Double = if (index == 0) 0.0 else SCORE_LIST[index].toDouble()

    override fun displayScore(track: DomainTrack): String = track.score.toString()

    override suspend fun update(track: Track, didReadChapter: Boolean): Track {
        if (track.status != COMPLETE_LIST && didReadChapter) {
            track.status = READING_LIST
        }
        api.updateSeriesListItem(track)
        return track
    }

    override suspend fun delete(track: DomainTrack) {
        api.deleteSeriesFromList(track)
    }

    override suspend fun bind(track: Track, hasReadChapters: Boolean): Track {
        return try {
            val (series, rating) = api.getSeriesListItem(track)
            track.copyFrom(series, rating)
        } catch (_: Exception) {
            track.score = 0.0
            api.addSeriesToList(track, hasReadChapters)
            track
        }
    }

    override suspend fun search(query: String): List<TrackSearch> {
        query.trackerSearchId(::seriesId)?.let { seriesId ->
            return api.getSeriesDetails(seriesId)?.let { listOf(it.toTrackSearch(id)) } ?: emptyList()
        }

        return api.search(query)
            .map {
                it.toTrackSearch(id)
            }
    }

    // RK --> novel-aware search: keep only "Novel"-type series from the unfiltered results
    override val supportsNovels = true

    override suspend fun searchNovel(query: String): List<TrackSearch> {
        query.trackerSearchId(::seriesId)?.let { seriesId ->
            return api.getSeriesDetails(seriesId)
                ?.takeIf { it.type?.equals("novel", ignoreCase = true) == true }
                ?.let { listOf(it.toTrackSearch(id)) }
                ?: emptyList()
        }

        return api.search(query, novel = true)
            .filter { it.type?.equals("novel", ignoreCase = true) == true }
            .map {
                it.toTrackSearch(id)
            }
    }

    /** A series id is written decimal or in the base 36 form the site's own URLs carry. */
    private fun seriesId(text: String): Long? = text.toLongOrNull() ?: text.toLongOrNull(36)
    // RK <--

    override suspend fun refresh(track: Track): Track {
        val (series, rating) = api.getSeriesListItem(track)
        return track.copyFrom(series, rating)
    }

    // RK --> autofill entry metadata (Fill from tracker). MangaUpdates splits one author list into
    // authors vs artists by each entry's type (see namesOfType); genres are a separate clean list.
    override suspend fun getMangaMetadata(track: DomainTrack): TrackMangaMetadata {
        val series = api.getSeries(track)
        return TrackMangaMetadata(
            remoteId = series.seriesId,
            title = series.title?.htmlDecode(),
            thumbnailUrl = series.image?.url?.original,
            description = series.description?.htmlDecode(),
            authors = series.authors.namesOfType("Author").joinToString(", ").ifEmpty { null },
            artists = series.authors.namesOfType("Artist").joinToString(", ").ifEmpty { null },
            genres = series.genres?.mapNotNull { it.genre }?.takeIf { it.isNotEmpty() },
        )
    }
    // RK <--

    private fun Track.copyFrom(item: MUListItem, rating: MURating?): Track = apply {
        item.copyTo(this)
        score = rating?.rating ?: 0.0
    }

    override suspend fun login(username: String, password: String) {
        val authenticated = api.authenticate(username, password)
        interceptor.newAuth(authenticated.sessionToken)
        val currentUser = api.getCurrentUser()
        saveDisplayUsername(currentUser.username)
        saveCredentials(authenticated.uid.toString(), authenticated.sessionToken)
    }

    fun restoreSession(): String? {
        return trackPreferences.trackPassword(this).get().ifBlank { null }
    }
}
