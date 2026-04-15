package social.waddle.android.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ListPublicWaddlesResponse(
    val waddles: List<WaddleSummary> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class WaddleSummary(
    val id: String,
    val name: String,
    val description: String? = null,
    @SerialName("owner_user_id")
    val ownerUserId: String? = null,
    @SerialName("icon_url")
    val iconUrl: String? = null,
    @SerialName("is_public")
    val isPublic: Boolean = false,
    val role: String? = null,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null,
)

@Serializable
data class ChannelSummary(
    val id: String,
    val name: String,
    @SerialName("waddle_id")
    val waddleId: String? = null,
    val description: String? = null,
    @SerialName("channel_type")
    val channelType: String? = null,
    val position: Int? = null,
    @SerialName("is_default")
    val isDefault: Boolean = false,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null,
)

@Serializable
data class MemberSummary(
    @SerialName("user_id")
    val userId: String,
    val username: String,
    @SerialName("avatar_url")
    val avatarUrl: String? = null,
    val role: String,
    @SerialName("joined_at")
    val joinedAt: String,
)

@Serializable
data class UserSearchResult(
    val id: String,
    val username: String,
    @SerialName("display_name")
    val displayName: String? = null,
    @SerialName("avatar_url")
    val avatarUrl: String? = null,
    val jid: String,
)

@Serializable
data class ListMembersResponse(
    val members: List<MemberSummary> = emptyList(),
)

@Serializable
data class SearchUsersResponse(
    val users: List<UserSearchResult> = emptyList(),
)

@Serializable
data class CreateChannelRequest(
    val name: String,
    val description: String? = null,
    @SerialName("channel_type")
    val channelType: String = "text",
    val position: Int? = null,
)

@Serializable
data class UpdateChannelRequest(
    val name: String? = null,
    val description: String? = null,
    val position: Int? = null,
)

@Serializable
data class CreateWaddleRequest(
    val name: String,
    val description: String? = null,
    @SerialName("is_public")
    val isPublic: Boolean = false,
)

@Serializable
data class UpdateWaddleRequest(
    val name: String? = null,
    val description: String? = null,
    @SerialName("is_public")
    val isPublic: Boolean? = null,
)

@Serializable
data class AddMemberRequest(
    @SerialName("user_id")
    val userId: String,
    val role: String = "member",
)

@Serializable
data class UpdateMemberRoleRequest(
    val role: String,
)

data class ChatMessageDraft(
    val channelId: String,
    val roomJid: String,
    val body: String,
    val stanzaId: String? = null,
    val replyToMessageId: String? = null,
    val correctionMessageId: String? = null,
    val mentions: List<String> = emptyList(),
    val sharedFile: ChatFileAttachment? = null,
)

data class ChatFileAttachment(
    val url: String,
    val name: String?,
    val mediaType: String,
    val size: Long,
    val description: String? = null,
    val disposition: String = "inline",
)

class OutboundFileAttachment(
    val name: String,
    val mediaType: String,
    val bytes: ByteArray,
)

data class XepFeature(
    val xep: String,
    val name: String,
    val status: FeatureStatus,
)

enum class FeatureStatus {
    Active,
    AdapterReady,
    Deferred,
}
