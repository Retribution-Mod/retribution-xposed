package io.github.retribution.xposed

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal val httpClient by lazy {
    HttpClient(CIO) {
        expectSuccess = false
        install(UserAgent) { agent = RetributionConstants.USER_AGENT }
        install(HttpRedirect) {}
        install(HttpTimeout) {}
    }
}

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
)

internal suspend fun HttpClient.getLatestReleaseTag(repo: String): String? = runCatching {
    val response = get("https://api.github.com/repos/$repo/releases/latest")
    if (response.status == HttpStatusCode.OK) {
        response.body<GitHubRelease>().tagName
    } else null
}.getOrNull()

internal sealed class ETagFetchResult {
    /** A fresh body was fetched. */
    class Fetched(val bytes: ByteArray, val etag: String?) : ETagFetchResult()

    /** The server responded `304 Not Modified`. The cached copy is up-to-date. */
    object NotModified : ETagFetchResult()
}

internal suspend fun HttpClient.getWithETag(
    url: String,
    etag: String?,
    timeoutMillis: Long? = null,
): ETagFetchResult {
    val response = get(url) {
        etag?.let { headers.append(HttpHeaders.IfNoneMatch, it) }
        timeoutMillis?.let { timeout { requestTimeoutMillis = it } }
    }

    return when (response.status) {
        HttpStatusCode.OK -> ETagFetchResult.Fetched(
            bytes = response.body(),
            etag = response.headers[HttpHeaders.ETag]?.takeIf { it.isNotEmpty() },
        )

        HttpStatusCode.NotModified -> ETagFetchResult.NotModified

        else -> throw ResponseException(response, "Received status: ${response.status}")
    }
}