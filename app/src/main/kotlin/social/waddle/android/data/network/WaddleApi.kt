package social.waddle.android.data.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.headers
import io.ktor.http.isSuccess
import social.waddle.android.data.model.AddMemberRequest
import social.waddle.android.data.model.AuthProviderSummary
import social.waddle.android.data.model.ChannelSummary
import social.waddle.android.data.model.CreateChannelRequest
import social.waddle.android.data.model.CreateWaddleRequest
import social.waddle.android.data.model.ListMembersResponse
import social.waddle.android.data.model.ListPublicWaddlesResponse
import social.waddle.android.data.model.MemberSummary
import social.waddle.android.data.model.SearchUsersResponse
import social.waddle.android.data.model.SessionResponse
import social.waddle.android.data.model.UpdateChannelRequest
import social.waddle.android.data.model.UpdateMemberRoleRequest
import social.waddle.android.data.model.UpdateWaddleRequest
import social.waddle.android.data.model.UserSearchResult
import social.waddle.android.data.model.WaddleEnvironment
import social.waddle.android.data.model.WaddleSummary
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WaddleApi private constructor(
    private val client: HttpClient,
    private val baseUrlOverride: String?,
) {
    @Inject
    constructor(client: HttpClient) : this(client, null)

    suspend fun authProviders(environment: WaddleEnvironment): List<AuthProviderSummary> =
        client
            .get("${baseUrl(environment)}/api/auth/providers")
            .body()

    suspend fun fetchSession(
        environment: WaddleEnvironment,
        sessionId: String,
    ): SessionResponse =
        client
            .get("${baseUrl(environment)}/api/auth/session") {
                parameter("session_id", sessionId)
            }.body()

    suspend fun publicWaddles(
        environment: WaddleEnvironment,
        sessionId: String,
        query: String? = null,
    ): List<WaddleSummary> =
        client
            .get("${baseUrl(environment)}/v1/waddles/public") {
                parameter("session_id", sessionId)
                parameter("limit", DEFAULT_LIMIT)
                query?.takeIf { it.isNotBlank() }?.let { parameter("query", it) }
            }.body<ListPublicWaddlesResponse>()
            .waddles

    suspend fun joinWaddle(
        environment: WaddleEnvironment,
        sessionId: String,
        waddleId: String,
    ): WaddleSummary =
        client
            .post("${baseUrl(environment)}/v1/waddles/$waddleId/join") {
                parameter("session_id", sessionId)
            }.body()

    suspend fun createWaddle(
        environment: WaddleEnvironment,
        sessionId: String,
        input: CreateWaddleRequest,
    ): WaddleSummary =
        client
            .post("${baseUrl(environment)}/v1/waddles") {
                parameter("session_id", sessionId)
                contentType(ContentType.Application.Json)
                setBody(input)
            }.body()

    suspend fun updateWaddle(
        environment: WaddleEnvironment,
        sessionId: String,
        waddleId: String,
        input: UpdateWaddleRequest,
    ): WaddleSummary =
        client
            .patch("${baseUrl(environment)}/v1/waddles/$waddleId") {
                parameter("session_id", sessionId)
                contentType(ContentType.Application.Json)
                setBody(input)
            }.body()

    suspend fun deleteWaddle(
        environment: WaddleEnvironment,
        sessionId: String,
        waddleId: String,
    ) {
        client.delete("${baseUrl(environment)}/v1/waddles/$waddleId") {
            parameter("session_id", sessionId)
        }
    }

    suspend fun createChannel(
        environment: WaddleEnvironment,
        sessionId: String,
        waddleId: String,
        input: CreateChannelRequest,
    ): ChannelSummary =
        client
            .post("${baseUrl(environment)}/v1/waddles/$waddleId/channels") {
                parameter("session_id", sessionId)
                contentType(ContentType.Application.Json)
                setBody(input)
            }.body()

    suspend fun updateChannel(
        environment: WaddleEnvironment,
        sessionId: String,
        waddleId: String,
        channelId: String,
        input: UpdateChannelRequest,
    ): ChannelSummary =
        client
            .patch("${baseUrl(environment)}/v1/channels/$channelId") {
                parameter("session_id", sessionId)
                parameter("waddle_id", waddleId)
                contentType(ContentType.Application.Json)
                setBody(input)
            }.body()

    suspend fun deleteChannel(
        environment: WaddleEnvironment,
        sessionId: String,
        waddleId: String,
        channelId: String,
    ) {
        client.delete("${baseUrl(environment)}/v1/channels/$channelId") {
            parameter("session_id", sessionId)
            parameter("waddle_id", waddleId)
        }
    }

    suspend fun listMembers(
        environment: WaddleEnvironment,
        sessionId: String,
        waddleId: String,
    ): List<MemberSummary> =
        client
            .get("${baseUrl(environment)}/v1/waddles/$waddleId/members") {
                parameter("session_id", sessionId)
            }.body<ListMembersResponse>()
            .members

    suspend fun searchUsers(
        environment: WaddleEnvironment,
        sessionId: String,
        query: String,
    ): List<UserSearchResult> =
        client
            .get("${baseUrl(environment)}/v1/users/search") {
                parameter("session_id", sessionId)
                parameter("query", query)
                parameter("limit", USER_SEARCH_LIMIT)
            }.body<SearchUsersResponse>()
            .users

    suspend fun uploadToSlot(
        putUrl: String,
        bytes: ByteArray,
        contentType: String,
        uploadHeaders: Map<String, String>,
    ) {
        val response: HttpResponse =
            client.put(putUrl) {
                headers {
                    if (uploadHeaders.keys.none { it.equals(HttpHeaders.ContentType, ignoreCase = true) }) {
                        append(HttpHeaders.ContentType, contentType)
                    }
                    uploadHeaders.forEach { (name, value) -> append(name, value) }
                }
                setBody(bytes)
            }
        if (!response.status.isSuccess()) {
            error("Upload failed: ${response.status}")
        }
    }

    suspend fun addMember(
        environment: WaddleEnvironment,
        sessionId: String,
        waddleId: String,
        userId: String,
        role: String,
    ): MemberSummary =
        client
            .post("${baseUrl(environment)}/v1/waddles/$waddleId/members") {
                parameter("session_id", sessionId)
                contentType(ContentType.Application.Json)
                setBody(AddMemberRequest(userId = userId, role = role))
            }.body()

    suspend fun updateMemberRole(
        environment: WaddleEnvironment,
        sessionId: String,
        waddleId: String,
        userId: String,
        role: String,
    ): MemberSummary =
        client
            .patch("${baseUrl(environment)}/v1/waddles/$waddleId/members/$userId") {
                parameter("session_id", sessionId)
                contentType(ContentType.Application.Json)
                setBody(UpdateMemberRoleRequest(role = role))
            }.body()

    suspend fun removeMember(
        environment: WaddleEnvironment,
        sessionId: String,
        waddleId: String,
        userId: String,
    ) {
        client.delete("${baseUrl(environment)}/v1/waddles/$waddleId/members/$userId") {
            parameter("session_id", sessionId)
        }
    }

    private fun baseUrl(environment: WaddleEnvironment): String = baseUrlOverride ?: environment.apiBaseUrl

    internal companion object {
        fun forBaseUrl(
            client: HttpClient,
            baseUrl: String,
        ): WaddleApi = WaddleApi(client, baseUrl.trimEnd('/'))

        private const val DEFAULT_LIMIT = 100
        private const val MESSAGE_LIMIT = 40
        private const val USER_SEARCH_LIMIT = 8
    }
}
