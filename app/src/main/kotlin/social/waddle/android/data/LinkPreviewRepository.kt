package social.waddle.android.data

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import social.waddle.android.data.db.LinkPreviewDao
import social.waddle.android.data.db.LinkPreviewEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches and caches OpenGraph (+ HTML meta fallback) previews for URLs that
 * appear in messages. Results are kept in Room for a day per URL so repeated
 * scrolls past the same message don't re-fetch.
 */
@Singleton
class LinkPreviewRepository
    @Inject
    constructor(
        private val httpClient: HttpClient,
        private val dao: LinkPreviewDao,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val mutablePreviews = MutableStateFlow<Map<String, LinkPreview>>(emptyMap())
        val previews: StateFlow<Map<String, LinkPreview>> = mutablePreviews.asStateFlow()
        private val inFlight = mutableSetOf<String>()

        /** Kick a background fetch for [url] if we don't already have a fresh one. */
        fun requestPreview(url: String) {
            synchronized(inFlight) {
                if (url in inFlight) return
                inFlight.add(url)
            }
            scope.launch {
                try {
                    resolveAndCache(url)?.let { preview ->
                        mutablePreviews.update { it + (url to preview) }
                    }
                } finally {
                    synchronized(inFlight) { inFlight.remove(url) }
                }
            }
        }

        private suspend fun resolveAndCache(url: String): LinkPreview? {
            val cached = dao.get(url)
            val now = System.currentTimeMillis()
            if (cached != null && now - cached.fetchedAtEpochMillis < CACHE_TTL_MILLIS) {
                return cached.toPreview()
            }
            val fetched = runCatching { fetchPreview(url) }.getOrNull()
            val entity =
                LinkPreviewEntity(
                    url = url,
                    title = fetched?.title,
                    description = fetched?.description,
                    imageUrl = fetched?.imageUrl,
                    siteName = fetched?.siteName,
                    fetchedAtEpochMillis = now,
                    empty = fetched == null,
                )
            dao.upsert(entity)
            return entity.toPreview()
        }

        private suspend fun fetchPreview(url: String): LinkPreview? =
            withContext(Dispatchers.IO) {
                val response =
                    httpClient.get(url) {
                        accept(ContentType.Text.Html)
                        header(HttpHeaders.UserAgent, USER_AGENT)
                        header(HttpHeaders.AcceptLanguage, "en,*;q=0.5")
                        timeout {
                            requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
                            socketTimeoutMillis = REQUEST_TIMEOUT_MILLIS
                        }
                    }

                if (!response.status.isSuccess()) return@withContext null
                if (response.contentType()?.match(ContentType.Text.Html) != true) return@withContext null
                val html = response.bodyAsText().take(MAX_HTML_BYTES)
                OpenGraphParser.parse(html = html, baseUri = url)
            }

        private fun LinkPreviewEntity.toPreview(): LinkPreview? {
            if (empty) return null
            val allEmpty = listOf(title, description, imageUrl, siteName).all { it.isNullOrBlank() }
            if (allEmpty) return null
            return LinkPreview(
                url = url,
                title = title,
                description = description,
                imageUrl = imageUrl,
                siteName = siteName,
            )
        }

        companion object {
            const val CACHE_TTL_MILLIS: Long = 24 * 60 * 60 * 1000L
            const val REQUEST_TIMEOUT_MILLIS: Long = 8_000L
            const val MAX_HTML_BYTES: Int = 120_000 // enough to reach <head> meta tags on most pages
            const val USER_AGENT: String = "WaddleBot/1.0 (+https://waddle.social)"
        }
    }

data class LinkPreview(
    val url: String,
    val title: String?,
    val description: String?,
    val imageUrl: String?,
    val siteName: String?,
)
