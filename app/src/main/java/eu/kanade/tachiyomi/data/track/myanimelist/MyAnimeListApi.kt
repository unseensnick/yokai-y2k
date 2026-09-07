package eu.kanade.tachiyomi.data.track.myanimelist

import android.net.Uri
import androidx.core.net.toUri
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.model.TrackMangaMetadata
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.data.track.myanimelist.dto.MALLibraryItem
import eu.kanade.tachiyomi.data.track.myanimelist.dto.MALLibraryResult
import eu.kanade.tachiyomi.data.track.myanimelist.dto.MALListItem
import eu.kanade.tachiyomi.data.track.myanimelist.dto.MALListItemStatus
import eu.kanade.tachiyomi.data.track.myanimelist.dto.MALManga
import eu.kanade.tachiyomi.data.track.myanimelist.dto.MALMangaMetadata
import eu.kanade.tachiyomi.data.track.myanimelist.dto.MALOAuth
import eu.kanade.tachiyomi.data.track.myanimelist.dto.MALSearchResult
import eu.kanade.tachiyomi.data.track.myanimelist.dto.MALUser
import eu.kanade.tachiyomi.network.DELETE
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import eu.kanade.tachiyomi.util.PkceUtil
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale
import tachiyomi.domain.track.model.Track as DomainTrack

class MyAnimeListApi(
    private val trackerId: Long,
    private val client: OkHttpClient,
    interceptor: MyAnimeListInterceptor,
) {

    private val json: Json by injectLazy()

    private val authClient = client.newBuilder().addInterceptor(interceptor).build()

    suspend fun getAccessToken(authCode: String): MALOAuth {
        return withIOContext {
            val formBody: RequestBody = FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("code", authCode)
                .add("code_verifier", codeVerifier)
                .add("grant_type", "authorization_code")
                .build()
            with(json) {
                client.newCall(POST("$BASE_OAUTH_URL/token", body = formBody))
                    .awaitSuccess()
                    .parseAs()
            }
        }
    }

    suspend fun getCurrentUser(): String {
        return withIOContext {
            val request = Request.Builder()
                .url("$BASE_API_URL/users/@me")
                .get()
                .build()
            with(json) {
                authClient.newCall(request)
                    .awaitSuccess()
                    .parseAs<MALUser>()
                    .name
            }
        }
    }

    suspend fun search(query: String, novel: Boolean = false): List<TrackSearch> {
        return withIOContext {
            val url = "$BASE_API_URL/manga".toUri().buildUpon()
                // MAL API throws a 400 when the query is over 64 characters...
                .appendQueryParameter("q", query.take(64))
                .appendQueryParameter("nsfw", "true")
                .appendQueryParameter("fields", SEARCH_FIELDS)
                .build()
            with(json) {
                authClient.newCall(GET(url.toString()))
                    .awaitSuccess()
                    .parseAs<MALSearchResult>()
                    .data
                    // RK --> light novels share the /manga endpoint with media_type containing "novel"
                    .filter { it.node.mediaType.contains("novel") == novel }
                    // RK <--
                    .map { parseSearchItem(it.node) }
            }
        }
    }

    suspend fun getMangaDetails(id: Int, novel: Boolean = false): TrackSearch? {
        return withIOContext {
            val url = "$BASE_API_URL/manga".toUri().buildUpon()
                .appendPath(id.toString())
                .appendQueryParameter("fields", SEARCH_FIELDS)
                .build()
            with(json) {
                authClient.newCall(GET(url.toString()))
                    .awaitSuccess()
                    .parseAs<MALManga>()
                    // RK: the same media-type split the title search filters on.
                    .takeIf { it.mediaType.contains("novel") == novel }
                    ?.let { parseSearchItem(it) }
            }
        }
    }

    // RK --> "Fill from tracker" metadata (ported from Komikku, plus genres).
    suspend fun getMangaMetadata(track: DomainTrack): TrackMangaMetadata {
        return withIOContext {
            val url = "$BASE_API_URL/manga".toUri().buildUpon()
                .appendPath(track.remoteId.toString())
                .appendQueryParameter(
                    "fields",
                    "id,title,synopsis,main_picture,genres,authors{first_name,last_name}",
                )
                .build()
            with(json) {
                authClient.newCall(GET(url.toString()))
                    .awaitSuccess()
                    .parseAs<MALMangaMetadata>()
                    .let { metadata ->
                        TrackMangaMetadata(
                            remoteId = metadata.id,
                            title = metadata.title,
                            thumbnailUrl = metadata.covers?.large?.ifEmpty { null },
                            description = metadata.synopsis,
                            authors = metadata.authors
                                // count "Story" or "Story & Art" as authors, like library entries do
                                .filter { it.role.contains("Story") }
                                .mapNotNull { it.node.getFullName() }
                                .joinToString()
                                .ifEmpty { null },
                            artists = metadata.authors
                                .filter { it.role == "Art" }
                                .mapNotNull { it.node.getFullName() }
                                .joinToString()
                                .ifEmpty { null },
                            genres = metadata.genres.map { it.name }.takeIf { it.isNotEmpty() },
                        )
                    }
            }
        }
    }
    // RK <--

    suspend fun updateItem(track: Track): Track {
        return withIOContext {
            val formBodyBuilder = FormBody.Builder()
                .add("status", track.toMyAnimeListStatus() ?: "reading")
                .add("is_rereading", (track.status == MyAnimeList.REREADING).toString())
                .add("score", track.score.toString())
                .add("num_chapters_read", track.last_chapter_read.toInt().toString())
            convertToIsoDate(track.started_reading_date)?.let {
                formBodyBuilder.add("start_date", it)
            }
            convertToIsoDate(track.finished_reading_date)?.let {
                formBodyBuilder.add("finish_date", it)
            }

            val request = Request.Builder()
                .url(mangaUrl(track.remote_id).toString())
                .put(formBodyBuilder.build())
                .build()
            with(json) {
                val response = authClient
                    .newCall(request)
                    .await()

                if (!response.isSuccessful) {
                    if (response.body.string().contains("invalid_content")) {
                        // MAL returns unapproved titles in search but does not allow adding them to the list
                        // returns 400 with this body: {"message":"Invalid content","error":"invalid_content"}
                        // These unapproved titles cannot be filtered out in search and are also returned by the
                        // endpoint we use for id prefix search
                        throw MALTitleNotApproved()
                    } else {
                        throw HttpException(response.code)
                    }
                }

                response
                    .parseAs<MALListItemStatus>()
                    .let { parseMangaItem(it, track) }
            }
        }
    }

    suspend fun deleteItem(track: DomainTrack) {
        withIOContext {
            authClient
                .newCall(DELETE(mangaUrl(track.remoteId).toString()))
                .awaitSuccess()
        }
    }

    suspend fun findListItem(track: Track): Track? {
        return withIOContext {
            val uri = "$BASE_API_URL/manga".toUri().buildUpon()
                .appendPath(track.remote_id.toString())
                .appendQueryParameter("fields", "num_chapters,my_list_status{start_date,finish_date}")
                .build()
            with(json) {
                authClient.newCall(GET(uri.toString()))
                    .awaitSuccess()
                    .parseAs<MALListItem>()
                    .let { item ->
                        track.total_chapters = item.numChapters
                        item.myListStatus?.let { parseMangaItem(it, track) }
                    }
            }
        }
    }

    suspend fun findListItems(query: String, offset: Int = 0): List<TrackSearch> {
        return withIOContext {
            val myListSearchResult = getListPage(offset)

            val matches = myListSearchResult.data
                .filter { it.node.title.contains(query, ignoreCase = true) }
                .map { parseSearchItem(it.node) }

            // Check next page if there's more
            if (!myListSearchResult.paging.next.isNullOrBlank()) {
                matches + findListItems(query, offset + LIST_PAGINATION_AMOUNT)
            } else {
                matches
            }
        }
    }

    private suspend fun getListPage(offset: Int): MALSearchResult {
        return withIOContext {
            val urlBuilder = "$BASE_API_URL/users/@me/mangalist".toUri().buildUpon()
                .appendQueryParameter("fields", SEARCH_FIELDS)
                .appendQueryParameter("limit", LIST_PAGINATION_AMOUNT.toString())
            if (offset > 0) {
                urlBuilder.appendQueryParameter("offset", offset.toString())
            }

            val request = Request.Builder()
                .url(urlBuilder.build().toString())
                .get()
                .build()
            with(json) {
                authClient.newCall(request)
                    .awaitSuccess()
                    .parseAs()
            }
        }
    }

    // RK --> full library pull for the recommendation taste profile. Pages /users/@me/mangalist with
    // genres + list_status inline (one node-field request per page, never per title).
    suspend fun getUserLibrary(): List<MALLibraryItem> {
        return withIOContext {
            val results = mutableListOf<MALLibraryItem>()
            var offset = 0
            while (true) {
                val page = getLibraryPage(offset)
                results += page.data
                if (page.paging.next.isNullOrBlank()) break
                offset += LIST_PAGINATION_AMOUNT
            }
            results
        }
    }

    private suspend fun getLibraryPage(offset: Int): MALLibraryResult {
        val urlBuilder = "$BASE_API_URL/users/@me/mangalist".toUri().buildUpon()
            .appendQueryParameter("fields", LIBRARY_FIELDS)
            .appendQueryParameter("limit", LIST_PAGINATION_AMOUNT.toString())
            .appendQueryParameter("nsfw", "true")
        if (offset > 0) {
            urlBuilder.appendQueryParameter("offset", offset.toString())
        }
        val request = Request.Builder()
            .url(urlBuilder.build().toString())
            .get()
            .build()
        return with(json) {
            authClient.newCall(request)
                .awaitSuccess()
                .parseAs()
        }
    }
    // RK <--

    private fun parseMangaItem(listStatus: MALListItemStatus, track: Track): Track {
        return track.apply {
            val isRereading = listStatus.isRereading
            status = if (isRereading) MyAnimeList.REREADING else getStatus(listStatus.status)
            last_chapter_read = listStatus.numChaptersRead
            score = listStatus.score.toDouble()
            listStatus.startDate?.let { started_reading_date = parseDate(it) }
            listStatus.finishDate?.let { finished_reading_date = parseDate(it) }
        }
    }

    private fun parseSearchItem(searchItem: MALManga): TrackSearch {
        return TrackSearch.create(trackerId).apply {
            remote_id = searchItem.id
            title = searchItem.title
            summary = searchItem.synopsis
            total_chapters = searchItem.numChapters
            score = searchItem.mean
            cover_url = searchItem.covers?.large.orEmpty()
            tracking_url = "https://myanimelist.net/manga/$remote_id"
            publishing_status = searchItem.status.replace("_", " ")
            publishing_type = searchItem.mediaType.replace("_", " ")
            start_date = searchItem.startDate ?: ""
            artists = searchItem.authors
                .filter { authorNode -> authorNode.role == "Art" }
                .mapNotNull { authorNode -> authorNode.node.getFullName() }
            authors = searchItem.authors
                // count all with "Story" or "Story & Art" as authors, like is done for library entries
                .filter { authorNode -> authorNode.role.contains("Story") }
                .mapNotNull { authorNode -> authorNode.node.getFullName() }
        }
    }

    private fun parseDate(isoDate: String): Long {
        val pattern = when (isoDate.length) {
            10 -> "yyyy-MM-dd"
            7 -> "yyyy-MM"
            4 -> "yyyy"
            else -> throw IllegalArgumentException("Unsupported date format: \"$isoDate\"")
        }
        return SimpleDateFormat(pattern, Locale.US).parse(isoDate)?.time ?: 0L
    }

    private fun convertToIsoDate(epochTime: Long): String? {
        if (epochTime == 0L) {
            return ""
        }
        return try {
            val outputDf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            outputDf.format(epochTime)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val CLIENT_ID = "c46c9e24640a64dad5be5ca7a1a53a0f"

        private const val BASE_OAUTH_URL = "https://myanimelist.net/v1/oauth2"
        private const val BASE_API_URL = "https://api.myanimelist.net/v2"

        private const val SEARCH_FIELDS =
            "id,title,synopsis,num_chapters,mean,main_picture,status,media_type,start_date,authors{first_name,last_name}"

        // RK: genres + reading status inline for the taste-profile library pull.
        private const val LIBRARY_FIELDS = "list_status,genres"

        private const val LIST_PAGINATION_AMOUNT = 250

        private var codeVerifier: String = ""

        fun authUrl(): Uri = "$BASE_OAUTH_URL/authorize".toUri().buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("code_challenge", getPkceChallengeCode())
            .appendQueryParameter("response_type", "code")
            .build()

        fun mangaUrl(id: Long): Uri = "$BASE_API_URL/manga".toUri().buildUpon()
            .appendPath(id.toString())
            .appendPath("my_list_status")
            .build()

        fun refreshTokenRequest(oauth: MALOAuth): Request {
            val formBody: RequestBody = FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("refresh_token", oauth.refreshToken)
                .add("grant_type", "refresh_token")
                .build()

            // Add the Authorization header manually as this particular
            // request is called by the interceptor itself so it doesn't reach
            // the part where the token is added automatically.
            val headers = Headers.Builder()
                .add("Authorization", "Bearer ${oauth.accessToken}")
                .build()

            return POST("$BASE_OAUTH_URL/token", body = formBody, headers = headers)
        }

        private fun getPkceChallengeCode(): String {
            codeVerifier = PkceUtil.generateCodeVerifier()
            return codeVerifier
        }
    }
}
