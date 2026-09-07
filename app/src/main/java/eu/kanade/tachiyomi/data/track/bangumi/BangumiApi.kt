package eu.kanade.tachiyomi.data.track.bangumi

import android.net.Uri
import androidx.core.net.toUri
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.bangumi.dto.BGMCollectionItem
import eu.kanade.tachiyomi.data.track.bangumi.dto.BGMCollectionResponse
import eu.kanade.tachiyomi.data.track.bangumi.dto.BGMCollectionsResult
import eu.kanade.tachiyomi.data.track.bangumi.dto.BGMOAuth
import eu.kanade.tachiyomi.data.track.bangumi.dto.BGMSearchResult
import eu.kanade.tachiyomi.data.track.bangumi.dto.BGMSubject
import eu.kanade.tachiyomi.data.track.bangumi.dto.BGMUser
import eu.kanade.tachiyomi.data.track.bangumi.dto.Infobox
import eu.kanade.tachiyomi.data.track.model.TrackMangaMetadata
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.CacheControl
import okhttp3.FormBody
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.injectLazy
import tachiyomi.domain.track.model.Track as DomainTrack

class BangumiApi(
    private val trackerId: Long,
    private val client: OkHttpClient,
    interceptor: BangumiInterceptor,
) {

    private val json: Json by injectLazy()

    private val authClient = client.newBuilder().addInterceptor(interceptor).build()

    suspend fun addLibManga(track: Track): Track {
        return withIOContext {
            val url = "$API_URL/v0/users/-/collections/${track.remote_id}"
            val body = buildJsonObject {
                put("type", track.toApiStatus())
                put("rate", track.score.toInt().coerceIn(0, 10))
                put("ep_status", track.last_chapter_read.toInt())
                put("private", track.private)
            }
                .toString()
                .toRequestBody()
            // Returns with 202 Accepted on success with no body
            authClient.newCall(POST(url, body = body, headers = headersOf("Content-Type", APP_JSON)))
                .awaitSuccess()
            track
        }
    }

    suspend fun updateLibManga(track: Track): Track {
        return withIOContext {
            val url = "$API_URL/v0/users/-/collections/${track.remote_id}"
            val body = buildJsonObject {
                put("type", track.toApiStatus())
                put("rate", track.score.toInt().coerceIn(0, 10))
                put("ep_status", track.last_chapter_read.toInt())
                put("private", track.private)
            }
                .toString()
                .toRequestBody()

            val request = Request.Builder()
                .url(url)
                .patch(body)
                .headers(headersOf("Content-Type", APP_JSON))
                .build()
            // Returns with 204 No Content
            authClient.newCall(request)
                .awaitSuccess()

            track
        }
    }

    suspend fun search(search: String): List<TrackSearch> {
        // This API is marked as experimental in the documentation
        // but that has been the case since 2022 with few significant
        // changes to the schema for this endpoint since
        // "实验性 API， 本 schema 和实际的 API 行为都可能随时发生改动"
        return withIOContext {
            val url = "$API_URL/v0/search/subjects?limit=20"
            val body = buildJsonObject {
                put("keyword", search)
                put("sort", "match")
                putJsonObject("filter") {
                    putJsonArray("type") {
                        add(1) // "Book" (书籍) type
                    }
                }
            }
                .toString()
                .toRequestBody()
            with(json) {
                authClient.newCall(POST(url, body = body, headers = headersOf("Content-Type", APP_JSON)))
                    .awaitSuccess()
                    .parseAs<BGMSearchResult>()
                    .data
                    .filter { it.platform == null || it.platform == "漫画" }
                    .map { it.toTrackSearch(trackerId) }
            }
        }
    }

    // RK --> "Fill from tracker" metadata (ported from Komikku). Title/authors come from the Chinese
    // fields; author key 作者, illustrator key 插画 (Komikku's 插图 is wrong). No clean genre field, so
    // genres are left unset. Author/artist are parsed out of the free-form `infobox` list.
    suspend fun getMangaMetadata(track: DomainTrack): TrackMangaMetadata {
        return withIOContext {
            with(json) {
                authClient.newCall(GET("$API_URL/v0/subjects/${track.remoteId}"))
                    .awaitSuccess()
                    .parseAs<BGMSubject>()
                    .let { subject ->
                        TrackMangaMetadata(
                            remoteId = subject.id,
                            title = subject.nameCn.ifBlank { subject.name },
                            thumbnailUrl = subject.images?.common,
                            description = subject.summary,
                            authors = subject.infoboxValues("作者").ifEmpty { null },
                            artists = subject.infoboxValues("插画").ifEmpty { null },
                        )
                    }
            }
        }
    }

    private fun BGMSubject.infoboxValues(keyContains: String): String =
        infobox
            .filter { keyContains in it.key }
            .filterIsInstance<Infobox.SingleValue>()
            .joinToString(", ") { it.value }
    // RK <--

    suspend fun getMangaDetails(id: Int): TrackSearch? {
        return withIOContext {
            val url = "$API_URL/v0/subjects/$id"

            with(json) {
                authClient.newCall(GET(url, headers = headersOf("Content-Type", APP_JSON)))
                    .awaitSuccess()
                    .parseAs<BGMSubject>()
                    .takeIf { it.platform == null || it.platform == "漫画" }
                    ?.toTrackSearch(trackerId)
            }
        }
    }

    suspend fun statusLibManga(track: Track, username: String): Track? {
        return withIOContext {
            val url = "$API_URL/v0/users/$username/collections/${track.remote_id}"
            with(json) {
                try {
                    authClient.newCall(GET(url, cache = CacheControl.FORCE_NETWORK))
                        .awaitSuccess()
                        .parseAs<BGMCollectionResponse>()
                        .let {
                            track.status = it.getStatus()
                            track.last_chapter_read = it.epStatus?.toDouble() ?: 0.0
                            track.score = it.rate?.toDouble() ?: 0.0
                            track.total_chapters = it.subject?.eps?.toLong() ?: 0L
                            track
                        }
                } catch (e: HttpException) {
                    if (e.code == 404) { // "subject is not collected by user"
                        null
                    } else {
                        throw e
                    }
                }
            }
        }
    }

    suspend fun accessToken(code: String): BGMOAuth {
        return withIOContext {
            val body = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("client_id", CLIENT_ID)
                .add("client_secret", CLIENT_SECRET)
                .add("code", code)
                .add("redirect_uri", REDIRECT_URL)
                .build()
            with(json) {
                client.newCall(POST(OAUTH_URL, body = body))
                    .awaitSuccess()
                    .parseAs<BGMOAuth>()
            }
        }
    }

    suspend fun getCurrentUser(): BGMUser {
        return withIOContext {
            with(json) {
                authClient.newCall(GET("$API_URL/v0/me"))
                    .awaitSuccess()
                    .parseAs<BGMUser>()
            }
        }
    }

    // RK --> full library pull for the recommendation taste profile. Pages the user's manga
    // collections (subject_type=1) with subject tags inline, 50/entry.
    suspend fun getUserLibrary(username: String): List<BGMCollectionItem> {
        return withIOContext {
            val results = mutableListOf<BGMCollectionItem>()
            var offset = 0
            while (true) {
                val page = fetchCollectionsPage(username, offset)
                results += page.data
                offset += COLLECTIONS_PAGE_LIMIT
                if (page.data.isEmpty() || offset >= page.total) break
            }
            results
        }
    }

    private suspend fun fetchCollectionsPage(username: String, offset: Int): BGMCollectionsResult {
        val url = "$API_URL/v0/users/$username/collections".toUri().buildUpon()
            .appendQueryParameter("subject_type", "1")
            .appendQueryParameter("limit", COLLECTIONS_PAGE_LIMIT.toString())
            .appendQueryParameter("offset", offset.toString())
            .build()
        return with(json) {
            authClient.newCall(GET(url.toString())).awaitSuccess().parseAs<BGMCollectionsResult>()
        }
    }
    // RK <--

    companion object {
        // RK: Bangumi collections page size for the taste-profile pull.
        private const val COLLECTIONS_PAGE_LIMIT = 50

        private const val CLIENT_ID = "bgm291665acbd06a4c28"
        private const val CLIENT_SECRET = "43e5ce36b207de16e5d3cfd3e79118db"

        private const val API_URL = "https://api.bgm.tv"
        private const val OAUTH_URL = "https://bgm.tv/oauth/access_token"
        private const val LOGIN_URL = "https://bgm.tv/oauth/authorize"

        private const val REDIRECT_URL = "mihon://bangumi-auth"

        private const val APP_JSON = "application/json"

        fun authUrl(): Uri =
            LOGIN_URL.toUri().buildUpon()
                .appendQueryParameter("client_id", CLIENT_ID)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("redirect_uri", REDIRECT_URL)
                .build()

        fun refreshTokenRequest(token: String) = POST(
            OAUTH_URL,
            body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", CLIENT_ID)
                .add("client_secret", CLIENT_SECRET)
                .add("refresh_token", token)
                .add("redirect_uri", REDIRECT_URL)
                .build(),
        )
    }
}
