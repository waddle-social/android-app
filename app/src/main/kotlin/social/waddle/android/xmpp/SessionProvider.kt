package social.waddle.android.xmpp

import social.waddle.android.data.model.StoredSession

/**
 * Abstraction the XMPP client uses to obtain a fresh [StoredSession] when
 * reconnecting. The default wiring backs this with `AuthRepository.ensureFreshSession()`
 * which refreshes the OAuth access token (and therefore the XMPP bearer token)
 * if it has expired during an outage.
 *
 * Declared in the `xmpp` package — `auth` depends on `xmpp`, so we invert the
 * dependency via this tiny interface to avoid a cycle.
 */
interface SessionProvider {
    suspend fun currentSession(): StoredSession?
}
