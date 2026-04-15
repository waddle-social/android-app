package social.waddle.android.data.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class WaddleModelsSerializationTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
        }

    @Test
    fun decodesPublicWaddleEnvelopeFromServer() {
        val response =
            json.decodeFromString<ListPublicWaddlesResponse>(
                """
                {
                  "waddles": [
                    {
                      "id": "waddle-1",
                      "name": "Waddle",
                      "description": "A public space",
                      "owner_user_id": "user-1",
                      "icon_url": "https://xmpp.waddle.social/icon.png",
                      "is_public": true,
                      "role": "member",
                      "created_at": "2026-04-14T10:00:00Z",
                      "updated_at": null
                    }
                  ],
                  "total": 1
                }
                """.trimIndent(),
            )

        val waddle = response.waddles.single()

        assertEquals(1, response.total)
        assertEquals("waddle-1", waddle.id)
        assertEquals("https://xmpp.waddle.social/icon.png", waddle.iconUrl)
        assertEquals("member", waddle.role)
    }
}
