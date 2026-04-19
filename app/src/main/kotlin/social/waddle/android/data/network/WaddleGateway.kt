package social.waddle.android.data.network

import social.waddle.android.data.model.AuthProviderSummary
import social.waddle.android.data.model.ChannelSummary
import social.waddle.android.data.model.CreateChannelRequest
import social.waddle.android.data.model.CreateWaddleRequest
import social.waddle.android.data.model.MemberSummary
import social.waddle.android.data.model.SessionResponse
import social.waddle.android.data.model.UpdateChannelRequest
import social.waddle.android.data.model.UpdateWaddleRequest
import social.waddle.android.data.model.UserSearchResult
import social.waddle.android.data.model.WaddleEnvironment
import social.waddle.android.data.model.WaddleSummary
import social.waddle.android.xmpp.XmppClient
import javax.inject.Inject
import javax.inject.Singleton

interface WaddleGateway : XmppClient {
    suspend fun authProviders(environment: WaddleEnvironment): List<AuthProviderSummary>

    suspend fun fetchSession(
        environment: WaddleEnvironment,
        sessionId: String,
    ): SessionResponse

    suspend fun publicWaddles(
        environment: WaddleEnvironment,
        sessionId: String,
        query: String? = null,
    ): List<WaddleSummary>

    suspend fun joinWaddle(
        environment: WaddleEnvironment,
        sessionId: String,
        waddleId: String,
    ): WaddleSummary

    suspend fun createWaddle(
        environment: WaddleEnvironment,
        sessionId: String,
        input: CreateWaddleRequest,
    ): WaddleSummary

    suspend fun updateWaddle(
        environment: WaddleEnvironment,
        sessionId: String,
        waddleId: String,
        input: UpdateWaddleRequest,
    ): WaddleSummary

    suspend fun deleteWaddle(
        environment: WaddleEnvironment,
        sessionId: String,
        waddleId: String,
    )

    suspend fun createChannel(
        environment: WaddleEnvironment,
        sessionId: String,
        waddleId: String,
        input: CreateChannelRequest,
    ): ChannelSummary

    suspend fun updateChannel(
        environment: WaddleEnvironment,
        sessionId: String,
        waddleId: String,
        channelId: String,
        input: UpdateChannelRequest,
    ): ChannelSummary

    suspend fun deleteChannel(
        environment: WaddleEnvironment,
        sessionId: String,
        waddleId: String,
        channelId: String,
    )

    suspend fun listMembers(
        environment: WaddleEnvironment,
        sessionId: String,
        waddleId: String,
    ): List<MemberSummary>

    suspend fun searchUsers(
        environment: WaddleEnvironment,
        sessionId: String,
        query: String,
    ): List<UserSearchResult>

    suspend fun uploadToSlot(
        putUrl: String,
        bytes: ByteArray,
        contentType: String,
        uploadHeaders: Map<String, String>,
    )

    suspend fun addMember(
        environment: WaddleEnvironment,
        sessionId: String,
        waddleId: String,
        userId: String,
        role: String,
    ): MemberSummary

    suspend fun updateMemberRole(
        environment: WaddleEnvironment,
        sessionId: String,
        waddleId: String,
        userId: String,
        role: String,
    ): MemberSummary

    suspend fun removeMember(
        environment: WaddleEnvironment,
        sessionId: String,
        waddleId: String,
        userId: String,
    )
}

@Singleton
class RealWaddleGateway
    @Inject
    constructor(
        private val api: WaddleApi,
        private val xmppClient: XmppClient,
    ) : WaddleGateway,
        XmppClient by xmppClient {
        override suspend fun authProviders(environment: WaddleEnvironment): List<AuthProviderSummary> = api.authProviders(environment)

        override suspend fun fetchSession(
            environment: WaddleEnvironment,
            sessionId: String,
        ): SessionResponse = api.fetchSession(environment, sessionId)

        override suspend fun publicWaddles(
            environment: WaddleEnvironment,
            sessionId: String,
            query: String?,
        ): List<WaddleSummary> = api.publicWaddles(environment, sessionId, query)

        override suspend fun joinWaddle(
            environment: WaddleEnvironment,
            sessionId: String,
            waddleId: String,
        ): WaddleSummary = api.joinWaddle(environment, sessionId, waddleId)

        override suspend fun createWaddle(
            environment: WaddleEnvironment,
            sessionId: String,
            input: CreateWaddleRequest,
        ): WaddleSummary = api.createWaddle(environment, sessionId, input)

        override suspend fun updateWaddle(
            environment: WaddleEnvironment,
            sessionId: String,
            waddleId: String,
            input: UpdateWaddleRequest,
        ): WaddleSummary = api.updateWaddle(environment, sessionId, waddleId, input)

        override suspend fun deleteWaddle(
            environment: WaddleEnvironment,
            sessionId: String,
            waddleId: String,
        ) = api.deleteWaddle(environment, sessionId, waddleId)

        override suspend fun createChannel(
            environment: WaddleEnvironment,
            sessionId: String,
            waddleId: String,
            input: CreateChannelRequest,
        ): ChannelSummary {
            val channel =
                xmppClient.createChannel(
                    waddleId = waddleId,
                    name = input.name,
                    description = input.description,
                    channelType = input.channelType,
                    position = input.position ?: 0,
                )
            return ChannelSummary(
                id = channel.id,
                name = channel.name,
                waddleId = waddleId,
                description = input.description,
                channelType = channel.channelType,
            )
        }

        override suspend fun updateChannel(
            environment: WaddleEnvironment,
            sessionId: String,
            waddleId: String,
            channelId: String,
            input: UpdateChannelRequest,
        ): ChannelSummary = api.updateChannel(environment, sessionId, waddleId, channelId, input)

        override suspend fun deleteChannel(
            environment: WaddleEnvironment,
            sessionId: String,
            waddleId: String,
            channelId: String,
        ) = api.deleteChannel(environment, sessionId, waddleId, channelId)

        override suspend fun listMembers(
            environment: WaddleEnvironment,
            sessionId: String,
            waddleId: String,
        ): List<MemberSummary> = api.listMembers(environment, sessionId, waddleId)

        override suspend fun searchUsers(
            environment: WaddleEnvironment,
            sessionId: String,
            query: String,
        ): List<UserSearchResult> = api.searchUsers(environment, sessionId, query)

        override suspend fun uploadToSlot(
            putUrl: String,
            bytes: ByteArray,
            contentType: String,
            uploadHeaders: Map<String, String>,
        ) = api.uploadToSlot(putUrl, bytes, contentType, uploadHeaders)

        override suspend fun addMember(
            environment: WaddleEnvironment,
            sessionId: String,
            waddleId: String,
            userId: String,
            role: String,
        ): MemberSummary = api.addMember(environment, sessionId, waddleId, userId, role)

        override suspend fun updateMemberRole(
            environment: WaddleEnvironment,
            sessionId: String,
            waddleId: String,
            userId: String,
            role: String,
        ): MemberSummary = api.updateMemberRole(environment, sessionId, waddleId, userId, role)

        override suspend fun removeMember(
            environment: WaddleEnvironment,
            sessionId: String,
            waddleId: String,
            userId: String,
        ) = api.removeMember(environment, sessionId, waddleId, userId)
    }
