package social.waddle.android.data.model

enum class WaddleEnvironment(
    val id: String,
    val displayName: String,
    val apiBaseUrl: String,
    val xmppHost: String,
    val xmppPort: Int,
    val requireTls: Boolean,
) {
    Prod(
        id = "prod",
        displayName = "Waddle",
        apiBaseUrl = "https://xmpp.waddle.social",
        xmppHost = "xmpp.waddle.social",
        xmppPort = 5222,
        requireTls = true,
    ),
    Dev(
        id = "dev",
        displayName = "Local dev",
        apiBaseUrl = "http://localhost:3000",
        xmppHost = "localhost",
        xmppPort = 5222,
        requireTls = false,
    ),
    ;

    companion object {
        fun fromId(id: String?): WaddleEnvironment = entries.firstOrNull { it.id == id } ?: Prod
    }
}
