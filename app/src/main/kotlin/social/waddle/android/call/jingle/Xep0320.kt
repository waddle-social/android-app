package social.waddle.android.call.jingle

/**
 * XEP-0320: Use of DTLS-SRTP in Jingle Sessions.
 *
 * Port of `server/crates/waddle-xmpp/src/xep/xep0320.rs`.
 */
object Xep0320 {
    const val NS: String = "urn:xmpp:jingle:apps:dtls:0"
}

enum class FingerprintSetup(
    val attr: String,
) {
    ACTPASS("actpass"),
    ACTIVE("active"),
    PASSIVE("passive"),
    HOLDCONN("holdconn"),
    ;

    companion object {
        fun fromAttr(value: String): FingerprintSetup? = entries.firstOrNull { it.attr == value }
    }
}

data class DtlsFingerprint(
    val hash: String,
    val value: String,
    var setup: FingerprintSetup? = null,
) {
    fun withSetup(setup: FingerprintSetup): DtlsFingerprint = copy(setup = setup)
}

fun JingleElement.isFingerprint(): Boolean = isTag("fingerprint", Xep0320.NS)

fun parseFingerprintElement(elem: JingleElement): DtlsFingerprint? {
    if (!elem.isFingerprint()) return null
    val hash = elem.attr("hash")?.takeIf { it.isNotEmpty() } ?: return null
    val value = elem.text
    if (value.isEmpty()) return null
    val setup = elem.attr("setup")?.let(FingerprintSetup::fromAttr)
    return DtlsFingerprint(hash = hash, value = value, setup = setup)
}

fun buildFingerprintElement(fingerprint: DtlsFingerprint): JingleElement =
    JingleElement
        .builder("fingerprint", Xep0320.NS)
        .attr("hash", fingerprint.hash)
        .attrIfNotNull("setup", fingerprint.setup?.attr)
        .text(fingerprint.value)
        .build()
