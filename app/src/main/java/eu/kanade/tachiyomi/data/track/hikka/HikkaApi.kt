package eu.kanade.tachiyomi.data.track.hikka

import android.net.Uri
import androidx.core.net.toUri
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.hikka.dto.HKManga
import eu.kanade.tachiyomi.data.track.hikka.dto.HKMangaPagination
import eu.kanade.tachiyomi.data.track.hikka.dto.HKOAuth
import eu.kanade.tachiyomi.data.track.hikka.dto.HKRead
import eu.kanade.tachiyomi.data.track.hikka.dto.HKUser
import eu.kanade.tachiyomi.data.track.model.TrackMangaMetadata
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.network.DELETE
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.PUT
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.jsonMime
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.injectLazy
import tachiyomi.domain.track.model.Track as DomainTrack

class HikkaApi(
    private val trackerId: Long,
    private val client: OkHttpClient,
    interceptor: HikkaInterceptor,
) {
    suspend fun getCurrentUser(): HKUser {
        return withIOContext {
            val request = Request.Builder()
                .url("${BASE_API_URL}/user/me")
                .get()
                .build()
            with(json) {
                authClient.newCall(request)
                    .awaitSuccess()
                    .parseAs<HKUser>()
            }
        }
    }

    suspend fun accessToken(reference: String): HKOAuth {
        return withIOContext {
            with(json) {
                client.newCall(authTokenCreate(reference))
                    .awaitSuccess()
                    .parseAs<HKOAuth>()
            }
        }
    }

    suspend fun searchManga(query: String): List<TrackSearch> = searchContent(query, "manga")

    // RK --> novel-aware search: Hikka keeps novels in a separate /novel content tree with the same
    // search body and result shape as /manga; toTrack tags the result so its bind path uses /novel.
    suspend fun searchNovel(query: String): List<TrackSearch> = searchContent(query, "novel")
    // RK <--

    private suspend fun searchContent(query: String, contentType: String): List<TrackSearch> {
        return withIOContext {
            val url = "$BASE_API_URL/$contentType".toUri().buildUpon()
                .appendQueryParameter("page", "1")
                .appendQueryParameter("size", "50")
                .build()

            val payload = buildJsonObject {
                put("media_type", buildJsonArray { })
                put("status", buildJsonArray { })
                put("only_translated", false)
                put("magazines", buildJsonArray { })
                put("genres", buildJsonArray { })
                put(
                    "score",
                    buildJsonArray {
                        add(0)
                        add(10)
                    },
                )
                put("query", query)
                put(
                    "sort",
                    buildJsonArray {
                        add("score:desc")
                        add("scored_by:desc")
                    },
                )
            }

            with(json) {
                authClient.newCall(POST(url.toString(), body = payload.toString().toRequestBody(jsonMime)))
                    .awaitSuccess()
                    .parseAs<HKMangaPagination>()
                    .list
                    .map { it.toTrack(trackerId, contentType) }
            }
        }
    }

    suspend fun getMangaDetails(slug: String): TrackSearch? = contentDetails(slug, "manga")

    // RK --> novel-aware id lookup, the same split the title search makes.
    suspend fun getNovelDetails(slug: String): TrackSearch? = contentDetails(slug, "novel")
    // RK <--

    private suspend fun contentDetails(slug: String, contentType: String): TrackSearch? {
        return withIOContext {
            val url = "$BASE_API_URL/$contentType/$slug"

            with(json) {
                val response = authClient.newCall(GET(url))
                    .await()

                if (response.code == 404) {
                    null
                } else {
                    response
                        .parseAs<HKManga>()
                        .toTrack(trackerId, contentType)
                }
            }
        }
    }

    // RK --> a bound track's URL is hikka.io/{contentType}/{slug}; Hikka splits manga and novel into
    // separate content trees, so every read/write path derives which tree from the URL segment.
    private fun contentTypeOf(url: String): String = url.split("/").getOrNull(3) ?: "manga"
    // RK <--

    suspend fun getRead(track: Track): HKRead? {
        return withIOContext {
            val slug = track.tracking_url.split("/")[4]
            val url = "$BASE_API_URL/read/${contentTypeOf(track.tracking_url)}/$slug".toUri().buildUpon().build()
            with(json) {
                try {
                    authClient.newCall(GET(url.toString()))
                        .awaitSuccess()
                        .parseAs<HKRead>()
                } catch (e: HttpException) {
                    if (e.code == 404) {
                        null
                    } else {
                        throw e
                    }
                }
            }
        }
    }

    suspend fun getManga(track: Track): TrackSearch {
        return withIOContext {
            val contentType = contentTypeOf(track.tracking_url)
            val slug = track.tracking_url.split("/")[4]
            val url = "$BASE_API_URL/$contentType/$slug".toUri().buildUpon()
                .build()

            with(json) {
                authClient.newCall(GET(url.toString()))
                    .awaitSuccess()
                    .parseAs<HKManga>()
                    .toTrack(trackerId, contentType)
            }
        }
    }

    // RK --> "Fill from tracker" metadata. No Komikku reference (Komikku has no Hikka); the /manga/{slug}
    // endpoint returns synopsis + credited people + genres, which the bind path doesn't read.
    suspend fun getMangaMetadata(track: DomainTrack): TrackMangaMetadata {
        return withIOContext {
            val slug = track.remoteUrl.split("/")[4]
            val url = "$BASE_API_URL/${contentTypeOf(track.remoteUrl)}/$slug".toUri().buildUpon().build()
            with(json) {
                authClient.newCall(GET(url.toString()))
                    .awaitSuccess()
                    .parseAs<HKManga>()
                    .let { manga ->
                        fun creditNames(roleMatch: String): String? =
                            manga.authors
                                .filter { author ->
                                    author.roles.any {
                                        it.nameEn?.contains(roleMatch, ignoreCase = true) ==
                                            true
                                    }
                                }
                                .mapNotNull { it.person?.run { nameEn ?: nameNative ?: nameUa } }
                                .filter { it.isNotBlank() }
                                .distinct()
                                .joinToString(", ")
                                .ifEmpty { null }

                        TrackMangaMetadata(
                            remoteId = track.remoteId,
                            title = manga.titleUa ?: manga.titleEn ?: manga.titleOriginal,
                            thumbnailUrl = manga.image,
                            // Hikka synopses are markdown with inline links ([text](url)); keep the text only.
                            description = (manga.synopsisUa ?: manga.synopsisEn)?.stripMarkdownLinks()?.ifBlank {
                                null
                            },
                            authors = creditNames("Story"),
                            artists = creditNames("Art"),
                            genres = manga.genres
                                .mapNotNull { it.nameEn ?: it.nameUa }
                                .filter { it.isNotBlank() }
                                .takeIf { it.isNotEmpty() },
                        )
                    }
            }
        }
    }

    private fun String.stripMarkdownLinks(): String =
        replace(Regex("""\[([^]]+)]\(([^)]+)\)"""), "$1")
    // RK <--

    suspend fun deleteUserManga(track: DomainTrack) {
        return withIOContext {
            val slug = track.remoteUrl.split("/")[4]

            val url = "$BASE_API_URL/read/${contentTypeOf(track.remoteUrl)}/$slug".toUri().buildUpon()
                .build()

            authClient.newCall(DELETE(url.toString()))
                .awaitSuccess()
        }
    }

    suspend fun addUserManga(track: Track): Track {
        return withIOContext {
            val contentType = contentTypeOf(track.tracking_url)
            val slug = track.tracking_url.split("/")[4]

            val url = "$BASE_API_URL/read/$contentType/$slug".toUri().buildUpon()
                .build()

            var rereads = getRead(track)?.rereads ?: 0
            if (track.status == Hikka.REREADING && rereads == 0) {
                rereads = 1
            }

            val payload = buildJsonObject {
                put("note", "")
                put("chapters", track.last_chapter_read.toInt())
                put("volumes", 0)
                put("rereads", rereads)
                put("score", track.score.toInt())
                put("status", track.toApiStatus())
                put("start_date", if (track.started_reading_date > 0L) track.started_reading_date / 1000 else null)
                put("end_date", if (track.finished_reading_date > 0L) track.finished_reading_date / 1000 else null)
            }

            with(json) {
                authClient.newCall(PUT(url.toString(), body = payload.toString().toRequestBody(jsonMime)))
                    .awaitSuccess()
                    .parseAs<HKRead>()
                    .toTrack(trackerId, contentType)
            }
        }
    }

    suspend fun updateUserManga(track: Track): Track = addUserManga(track)

    private val json: Json by injectLazy()
    private val authClient = client.newBuilder().addInterceptor(interceptor).build()

    companion object {
        const val BASE_API_URL = "https://api.hikka.io"
        const val BASE_URL = "https://hikka.io"
        private const val SCOPE = "readlist,read:user-details"
        private const val CLIENT_REFERENCE = "598ef1f5-b9d2-4e66-8b65-06949d5e14fc"
        private const val CLIENT_SECRET = "OKwzrNOZxq40psFgfcCUYddnvaeZWDnd34rt7fdcB5GmHoBBQuNTWX" +
            "61sZs8KECEWVXtMUDtq8QC4t9WX4DwWWYLXEVlgnlUXGT1fWCb-18c" +
            "Zd2m8Co-8HN6JQcjoP-B"

        fun authUrl(): Uri = "$BASE_URL/oauth".toUri().buildUpon()
            .appendQueryParameter("reference", CLIENT_REFERENCE)
            .appendQueryParameter("scope", SCOPE)
            .build()

        fun refreshTokenRequest(accessToken: String): Request {
            val headers = Headers.Builder()
                .add("auth", accessToken)
                .build()

            return GET("$BASE_API_URL/user/me", headers = headers) // Any request with auth
        }

        fun authTokenCreate(reference: String): Request {
            val payload = buildJsonObject {
                put("request_reference", reference)
                put("client_secret", CLIENT_SECRET)
            }
            return POST("$BASE_API_URL/auth/token", body = payload.toString().toRequestBody(jsonMime))
        }

        fun authTokenInfo(accessToken: String): Request {
            val headers = Headers.Builder()
                .add("auth", accessToken)
                .build()

            return GET("$BASE_API_URL/auth/token/info", headers = headers)
        }
    }
}
