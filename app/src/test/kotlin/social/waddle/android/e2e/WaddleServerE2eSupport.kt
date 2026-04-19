package social.waddle.android.e2e

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.robolectric.RuntimeEnvironment
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import social.waddle.android.data.model.StoredSession
import social.waddle.android.data.model.WaddleEnvironment
import social.waddle.android.data.network.RealWaddleGateway
import social.waddle.android.data.network.WaddleApi
import social.waddle.android.data.network.WaddleGateway
import social.waddle.android.xmpp.SessionProvider
import social.waddle.android.xmpp.SmackXmppClient
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.inject.Provider

private const val DOMAIN = "localhost"
private const val HTTP_PORT = 3000
private const val CONTAINER_DB_DIR = "/tmp/waddle-db"

internal const val E2E_USERNAME = "admin"
internal const val E2E_PASSWORD = "admin"
internal const val E2E_BARE_JID = "$E2E_USERNAME@$DOMAIN"

internal class WaddleServerContainer :
    GenericContainer<WaddleServerContainer>(DockerImageName.parse("ghcr.io/waddle-social/waddle:main")) {
    private val databaseDirectory: Path = Files.createTempDirectory("waddle-e2e-db-")

    init {
        addFixedExposedPort(HTTP_PORT, HTTP_PORT)
        withFileSystemBind(databaseDirectory.toString(), CONTAINER_DB_DIR, BindMode.READ_WRITE)
        withEnv("RUST_LOG", "warn,waddle_server=info")
        withEnv("WADDLE_BASE_URL", "http://127.0.0.1:$HTTP_PORT")
        withEnv("WADDLE_CERTS_EPHEMERAL", "true")
        withEnv("WADDLE_DB_PATH", CONTAINER_DB_DIR)
        withEnv("WADDLE_HTTP_ADDR", "0.0.0.0:$HTTP_PORT")
        withEnv("WADDLE_NATIVE_AUTH_ENABLED", "true")
        withEnv("WADDLE_TEST_FIXED_ACCOUNT_ENABLED", "true")
        withEnv("WADDLE_TEST_FIXED_ACCOUNT_PASSWORD", E2E_PASSWORD)
        withEnv("WADDLE_TEST_FIXED_ACCOUNT_USERNAME", E2E_USERNAME)
        withEnv("WADDLE_UPLOAD_DIR", "/tmp/waddle-uploads")
        withEnv("WADDLE_XMPP_DOMAIN", DOMAIN)
        withEnv("WADDLE_XMPP_MAM_DB", ":memory:")
        waitingFor(
            Wait
                .forHttp("/health")
                .forStatusCode(200)
                .withStartupTimeout(Duration.ofMinutes(4)),
        )
    }

    val httpBaseUrl: String
        get() = "http://$host:${getMappedPort(HTTP_PORT)}"

    val websocketUrl: String
        get() = "ws://$host:${getMappedPort(HTTP_PORT)}/xmpp-websocket"

    fun toHostHttpUrl(containerUrl: String): String {
        val uri = URI(containerUrl)
        return URI(
            uri.scheme,
            uri.userInfo,
            host,
            getMappedPort(HTTP_PORT),
            uri.path,
            uri.query,
            uri.fragment,
        ).toString()
    }

    fun connectedGateway(username: String = E2E_USERNAME): ProductionWaddleGateway {
        val session = seedOAuthSession(username)
        val http = e2eHttpClient()
        val context = RuntimeEnvironment.getApplication().applicationContext
        val sessionProvider =
            object : SessionProvider {
                override suspend fun currentSession(): StoredSession = session
            }
        val xmppClient =
            SmackXmppClient(
                context = context as Context,
                sessionProvider = Provider { sessionProvider },
            )
        val gateway = RealWaddleGateway(WaddleApi(http), xmppClient)
        runBlocking { gateway.connect(session, session.environment) }
        return ProductionWaddleGateway(gateway, session, http)
    }

    fun seedOAuthSession(username: String = E2E_USERNAME): StoredSession {
        val sessionId = "android-e2e-${UUID.randomUUID()}"
        val now = Instant.now()
        val expiresAt = now.plus(Duration.ofHours(4))
        val jid = bareJid(username)
        DriverManager.getConnection("jdbc:sqlite:${databaseDirectory.resolve("global.db")}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA busy_timeout=5000")
            }
            upsertUser(connection, username, now.toString())
            connection
                .prepareStatement(
                    """
                    INSERT INTO sessions (id, user_id, token_hash, expires_at, created_at, last_used_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, sessionId)
                    statement.setString(2, jid)
                    statement.setString(3, sha256Hex(sessionId))
                    statement.setString(4, expiresAt.toString())
                    statement.setString(5, now.toString())
                    statement.setString(6, now.toString())
                    statement.executeUpdate()
                }
        }
        return StoredSession(
            environmentId = WaddleEnvironment.Dev.id,
            accessToken = sessionId,
            refreshToken = null,
            accessTokenExpiresAt = expiresAt.toString(),
            sessionId = sessionId,
            userId = jid,
            username = username,
            avatarUrl = null,
            xmppLocalpart = username,
            jid = jid,
            xmppWebSocketUrl = websocketUrl,
            expiresAt = expiresAt.toString(),
        )
    }

    fun seedUser(username: String): String {
        val now = Instant.now().toString()
        DriverManager.getConnection("jdbc:sqlite:${databaseDirectory.resolve("global.db")}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA busy_timeout=5000")
            }
            upsertUser(connection, username, now)
        }
        return bareJid(username)
    }

    private fun e2eHttpClient(): HttpClient =
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                    },
                )
            }
        }

    private fun sha256Hex(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun upsertUser(
        connection: java.sql.Connection,
        username: String,
        now: String,
    ) {
        connection
            .prepareStatement(
                """
                INSERT OR IGNORE INTO users (id, username, xmpp_localpart, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, bareJid(username))
                statement.setString(2, username)
                statement.setString(3, username)
                statement.setString(4, now)
                statement.setString(5, now)
                statement.executeUpdate()
            }
    }
}

internal class ProductionWaddleGateway(
    val gateway: WaddleGateway,
    val session: StoredSession,
    private val http: HttpClient,
) : AutoCloseable {
    override fun close() {
        runBlocking { gateway.disconnect() }
        http.close()
    }
}

internal object WaddleE2eServer {
    private var container: WaddleServerContainer? = null

    fun get(): WaddleServerContainer =
        synchronized(this) {
            container ?: WaddleServerContainer().also { next ->
                next.start()
                container = next
            }
        }

    fun stop() {
        synchronized(this) {
            container?.stop()
            container = null
        }
    }
}

internal fun roomJid(prefix: String): String = "$prefix-${UUID.randomUUID()}@muc.$DOMAIN"

internal fun shortId(): String = UUID.randomUUID().toString().substringBefore('-')

internal fun bareJid(username: String): String = "$username@$DOMAIN"
