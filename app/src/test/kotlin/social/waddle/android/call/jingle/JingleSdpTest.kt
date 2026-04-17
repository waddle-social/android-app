package social.waddle.android.call.jingle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of the Rust test suite in `server/crates/waddle-xmpp/src/sfu/sdp.rs`
 * tests module. Each Kotlin test corresponds to the similarly-named Rust
 * test to keep behavior byte-identical. If the server-side test moves, the
 * matching Kotlin test needs the same update.
 */
class JingleSdpTest {
    private fun makeAudioContent(): JingleContent {
        val payload =
            RtpPayloadType(
                id = 111,
                name = "opus",
                clockrate = 48000L,
                channels = 2,
                parameters = mutableListOf(RtpParameter("minptime", "10")),
            )
        val desc = RtpDescription(media = MediaType.Audio, rtcpMux = true)
        desc.payloadTypes.add(payload)

        val candidate =
            IceUdpCandidate(
                foundation = "1",
                component = 1,
                protocol = "udp",
                priority = 2130706431L,
                ip = "192.0.2.1",
                port = 3478,
                candidateType = CandidateType.Host,
            )

        val transport = IceUdpTransport(ufrag = "abc", pwd = "xyz123")
        transport.fingerprints.add(
            DtlsFingerprint("sha-256", "AA:BB:CC:DD").withSetup(FingerprintSetup.ACTPASS),
        )
        transport.candidates.add(candidate)

        return JingleContent(
            creator = ContentCreator.INITIATOR,
            name = "audio",
            senders = Senders.BOTH,
            description = buildRtpDescriptionElement(desc),
            transport = buildIceUdpTransportElement(transport),
        )
    }

    private fun makeVideoContent(): JingleContent {
        val payload =
            RtpPayloadType(id = 96, name = "VP8", clockrate = 90000L, channels = null)
        val desc = RtpDescription(media = MediaType.Video, rtcpMux = true)
        desc.payloadTypes.add(payload)

        val transport = IceUdpTransport(ufrag = "def", pwd = "pwd456")
        transport.fingerprints.add(
            DtlsFingerprint("sha-256", "EE:FF:00:11").withSetup(FingerprintSetup.ACTIVE),
        )

        return JingleContent(
            creator = ContentCreator.INITIATOR,
            name = "video",
            senders = Senders.BOTH,
            description = buildRtpDescriptionElement(desc),
            transport = buildIceUdpTransportElement(transport),
        )
    }

    @Test
    fun convertsStandardJingleToSdp() {
        val sdp = jingleContentsToSdp(listOf(makeAudioContent()))

        assertContains(sdp, "v=0\r\n")
        assertContains(sdp, "o=- 0 0 IN IP4 0.0.0.0\r\n")
        assertContains(sdp, "s=-\r\n")
        assertContains(sdp, "t=0 0\r\n")
        assertContains(sdp, "a=group:BUNDLE audio\r\n")
        assertContains(sdp, "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n")
        assertContains(sdp, "a=mid:audio\r\n")
        assertContains(sdp, "a=sendrecv\r\n")
        assertContains(sdp, "a=rtpmap:111 opus/48000/2\r\n")
        assertContains(sdp, "a=fmtp:111 minptime=10\r\n")
        assertContains(sdp, "a=rtcp-mux\r\n")
        assertContains(sdp, "a=ice-ufrag:abc\r\n")
        assertContains(sdp, "a=ice-pwd:xyz123\r\n")
        assertContains(sdp, "a=fingerprint:sha-256 AA:BB:CC:DD\r\n")
        assertContains(sdp, "a=setup:actpass\r\n")
        assertContains(sdp, "a=candidate:1 1 udp 2130706431 192.0.2.1 3478 typ host\r\n")
    }

    @Test
    fun convertsMultiContentJingle() {
        val sdp = jingleContentsToSdp(listOf(makeAudioContent(), makeVideoContent()))
        assertContains(sdp, "a=group:BUNDLE audio video\r\n")
        assertContains(sdp, "m=audio 9")
        assertContains(sdp, "m=video 9")
        assertContains(sdp, "a=mid:audio\r\n")
        assertContains(sdp, "a=mid:video\r\n")
        // No channels suffix for VP8 (channels == null)
        assertContains(sdp, "a=rtpmap:96 VP8/90000\r\n")
    }

    @Test
    fun sdpRoundtripPreservesStructure() {
        val audio = makeAudioContent()
        val video = makeVideoContent()
        val sdp = jingleContentsToSdp(listOf(audio, video))
        val roundtripped = sdpToJingleContents(sdp, ContentCreator.INITIATOR)

        assertEquals(2, roundtripped.size)

        val rtAudio = roundtripped[0]
        assertEquals("audio", rtAudio.name)
        val audioDesc = parseRtpDescriptionElement(rtAudio.description!!)!!
        assertEquals(MediaType.Audio, audioDesc.media)
        assertTrue(audioDesc.rtcpMux)
        assertEquals(1, audioDesc.payloadTypes.size)
        assertEquals(111, audioDesc.payloadTypes[0].id)
        assertEquals("opus", audioDesc.payloadTypes[0].name)
        assertEquals(48000L, audioDesc.payloadTypes[0].clockrate)
        assertEquals(2, audioDesc.payloadTypes[0].channels)

        val audioTransport = parseIceUdpTransportElement(rtAudio.transport!!)!!
        assertEquals("abc", audioTransport.ufrag)
        assertEquals(1, audioTransport.fingerprints.size)
        assertEquals("sha-256", audioTransport.fingerprints[0].hash)
        assertEquals(FingerprintSetup.ACTPASS, audioTransport.fingerprints[0].setup)

        val rtVideo = roundtripped[1]
        assertEquals("video", rtVideo.name)
        val videoDesc = parseRtpDescriptionElement(rtVideo.description!!)!!
        assertEquals(MediaType.Video, videoDesc.media)
        assertTrue(videoDesc.rtcpMux)
        assertEquals(96, videoDesc.payloadTypes[0].id)
    }

    @Test
    fun parsesSdpCandidateLine() {
        val c = parseSdpCandidateLine("1 1 udp 2130706431 192.0.2.1 3478 typ host generation 0")!!
        assertEquals("1", c.foundation)
        assertEquals(1, c.component)
        assertEquals("udp", c.protocol)
        assertEquals(2130706431L, c.priority)
        assertEquals("192.0.2.1", c.ip)
        assertEquals(3478, c.port)
        assertEquals(CandidateType.Host, c.candidateType)
        assertEquals(0, c.generation)
    }

    @Test
    fun parsesSdpCandidateLineWithoutGeneration() {
        val c = parseSdpCandidateLine("2 1 tcp 1015021823 10.0.0.1 9 typ srflx")!!
        assertEquals("2", c.foundation)
        assertEquals(CandidateType.Srflx, c.candidateType)
        assertNull(c.generation)
    }

    @Test
    fun extractSdpFromStandardJingleElement() {
        val jingle =
            jingleFromXml(
                """
                <jingle xmlns='urn:xmpp:jingle:1' action='session-initiate' sid='test-sid'>
                  <content xmlns='urn:xmpp:jingle:1' creator='initiator' name='audio' senders='both'>
                    <description xmlns='urn:xmpp:jingle:apps:rtp:1' media='audio'>
                      <payload-type xmlns='urn:xmpp:jingle:apps:rtp:1' id='111' name='opus' clockrate='48000' channels='2'/>
                      <rtcp-mux xmlns='urn:xmpp:jingle:apps:rtp:1'/>
                    </description>
                    <transport xmlns='urn:xmpp:jingle:transports:ice-udp:1' ufrag='u1' pwd='p1'>
                      <fingerprint xmlns='urn:xmpp:jingle:apps:dtls:0' hash='sha-256' setup='actpass'>AB:CD:EF</fingerprint>
                      <candidate xmlns='urn:xmpp:jingle:transports:ice-udp:1' foundation='1' component='1' protocol='udp' priority='2130706431' ip='192.0.2.1' port='3478' type='host'/>
                    </transport>
                  </content>
                </jingle>
                """.trimIndent(),
            )
        val sdp = extractSdpOfferFromJingle(jingle)
        assertContains(sdp, "v=0\r\n")
        assertContains(sdp, "m=audio 9")
        assertContains(sdp, "a=rtpmap:111 opus/48000/2")
        assertContains(sdp, "a=ice-ufrag:u1")
        assertContains(sdp, "a=fingerprint:sha-256 AB:CD:EF")
        assertContains(sdp, "a=candidate:1 1 udp 2130706431 192.0.2.1 3478 typ host")
    }

    @Test
    fun buildSessionAcceptProducesValidJingle() {
        val sdpAnswer =
            """
            v=0
            o=- 0 0 IN IP4 0.0.0.0
            s=-
            t=0 0
            a=group:BUNDLE audio
            m=audio 9 UDP/TLS/RTP/SAVPF 111
            c=IN IP4 0.0.0.0
            a=mid:audio
            a=sendrecv
            a=rtpmap:111 opus/48000/2
            a=rtcp-mux
            a=ice-ufrag:resp-u
            a=ice-pwd:resp-p
            a=fingerprint:sha-256 11:22:33:44
            a=setup:active

            """.trimIndent().replace("\n", "\r\n")

        val element = buildJingleSessionAccept("sid-99", "sfu.waddle.social", sdpAnswer)
        assertEquals("session-accept", element.attr("action"))
        assertEquals("sid-99", element.attr("sid"))
        assertEquals("sfu.waddle.social", element.attr("responder"))
        assertEquals(Xep0166.NS, element.namespace)

        val contents = element.childrenOfTag("content", Xep0166.NS)
        assertEquals(1, contents.size)
        val contentEl = contents[0]
        assertEquals("initiator", contentEl.attr("creator"))
        assertEquals("audio", contentEl.attr("name"))

        val desc = contentEl.firstChild("description", Xep0167.NS)!!
        assertEquals("audio", desc.attr("media"))

        val transport = contentEl.firstChild("transport", Xep0176.NS)!!
        assertEquals("resp-u", transport.attr("ufrag"))
        assertEquals("resp-p", transport.attr("pwd"))
    }

    @Test
    fun jingleOfferWithHdrextEmitsSdpExtmapLine() {
        val jingle =
            jingleFromXml(
                """
                <jingle xmlns='urn:xmpp:jingle:1' action='session-initiate' sid='t'>
                  <content xmlns='urn:xmpp:jingle:1' creator='initiator' name='audio' senders='both'>
                    <description xmlns='urn:xmpp:jingle:apps:rtp:1' media='audio'>
                      <payload-type xmlns='urn:xmpp:jingle:apps:rtp:1' id='111' name='opus' clockrate='48000' channels='2'/>
                      <rtp-hdrext xmlns='urn:xmpp:jingle:apps:rtp:rtp-hdrext:0' id='4' uri='urn:ietf:params:rtp-hdrext:sdes:mid'/>
                      <rtp-hdrext xmlns='urn:xmpp:jingle:apps:rtp:rtp-hdrext:0' id='7' uri='urn:ietf:params:rtp-hdrext:ssrc-audio-level' senders='initiator'/>
                    </description>
                    <transport xmlns='urn:xmpp:jingle:transports:ice-udp:1'/>
                  </content>
                </jingle>
                """.trimIndent(),
            )
        val sdp = extractSdpOfferFromJingle(jingle)
        assertContains(sdp, "a=extmap:4 urn:ietf:params:rtp-hdrext:sdes:mid\r\n")
        assertContains(sdp, "a=extmap:7/sendonly urn:ietf:params:rtp-hdrext:ssrc-audio-level\r\n")
    }

    @Test
    fun sdpExtmapLineBecomesJingleRtpHdrext() {
        val sdpAnswer =
            """
            v=0
            o=- 0 0 IN IP4 0.0.0.0
            s=-
            t=0 0
            a=group:BUNDLE 0
            m=audio 9 UDP/TLS/RTP/SAVPF 111
            c=IN IP4 0.0.0.0
            a=mid:0
            a=sendrecv
            a=rtpmap:111 opus/48000/2
            a=extmap:4 urn:ietf:params:rtp-hdrext:sdes:mid
            a=extmap:7/sendonly urn:ietf:params:rtp-hdrext:ssrc-audio-level
            a=rtcp-mux
            a=ice-ufrag:u
            a=ice-pwd:p
            a=fingerprint:sha-256 11:22
            a=setup:active

            """.trimIndent().replace("\n", "\r\n")
        val jingle = buildJingleSessionAccept("sid-1", "sfu@x", sdpAnswer)
        val content = jingle.firstChild("content", Xep0166.NS)!!
        val desc = content.children.first { it.name == "description" }
        val exts =
            desc.children
                .filter { it.isTag("rtp-hdrext", JingleSdp.NS_JINGLE_RTP_HDREXT) }
                .map { Triple(it.attr("id"), it.attr("uri"), it.attr("senders")) }

        assertEquals(
            listOf(
                Triple("4", "urn:ietf:params:rtp-hdrext:sdes:mid", null),
                Triple(
                    "7",
                    "urn:ietf:params:rtp-hdrext:ssrc-audio-level",
                    "initiator",
                ),
            ),
            exts,
        )
    }

    @Test
    fun extractsBundleMidsFromSdp() {
        val sdp = "v=0\r\no=- 0 0 IN IP4 0.0.0.0\r\ns=-\r\nt=0 0\r\na=group:BUNDLE audio video\r\n"
        assertEquals(listOf("audio", "video"), extractBundleMids(sdp))
    }

    @Test
    fun extractBundleMidsReturnsEmptyWithoutGroupLine() {
        val sdp = "v=0\r\no=- 0 0 IN IP4 0.0.0.0\r\ns=-\r\nt=0 0\r\n"
        assertTrue(extractBundleMids(sdp).isEmpty())
    }

    @Test
    fun sessionAcceptIncludesXep0338BundleGroup() {
        val sdpAnswer =
            """
            v=0
            o=- 0 0 IN IP4 0.0.0.0
            s=-
            t=0 0
            a=group:BUNDLE 0 1
            m=audio 9 UDP/TLS/RTP/SAVPF 111
            c=IN IP4 0.0.0.0
            a=mid:0
            a=sendrecv
            a=rtpmap:111 opus/48000/2
            a=rtcp-mux
            a=ice-ufrag:ru
            a=ice-pwd:rp
            a=fingerprint:sha-256 11:22:33
            a=setup:active
            m=video 9 UDP/TLS/RTP/SAVPF 96
            c=IN IP4 0.0.0.0
            a=mid:1
            a=sendrecv
            a=rtpmap:96 VP8/90000
            a=rtcp-mux
            a=ice-ufrag:ru
            a=ice-pwd:rp
            a=fingerprint:sha-256 11:22:33
            a=setup:active

            """.trimIndent().replace("\n", "\r\n")

        val element = buildJingleSessionAccept("sid-42", "sfu@x", sdpAnswer)
        val group = element.firstChild("group", JingleSdp.NS_JINGLE_GROUPING)
        assertNotNull(group)
        assertEquals("BUNDLE", group!!.attr("semantics"))

        val names =
            group
                .childrenOfTag("content", JingleSdp.NS_JINGLE_GROUPING)
                .mapNotNull { it.attr("name") }
        assertEquals(listOf("0", "1"), names)
    }

    @Test
    fun sessionAcceptOmitsGroupWhenNoBundleInSdp() {
        val sdpAnswer =
            """
            v=0
            o=- 0 0 IN IP4 0.0.0.0
            s=-
            t=0 0
            m=audio 9 UDP/TLS/RTP/SAVPF 111
            c=IN IP4 0.0.0.0
            a=mid:audio
            a=sendrecv
            a=rtpmap:111 opus/48000/2
            a=rtcp-mux
            a=ice-ufrag:u
            a=ice-pwd:p
            a=fingerprint:sha-256 11:22:33
            a=setup:active

            """.trimIndent().replace("\n", "\r\n")

        val element = buildJingleSessionAccept("sid-1", "sfu@x", sdpAnswer)
        val hasGroup = element.children.any { it.isTag("group", JingleSdp.NS_JINGLE_GROUPING) }
        assertFalse(hasGroup)
    }

    @Test
    fun extractsSidAndAction() {
        val element =
            JingleElement
                .builder("jingle", Xep0166.NS)
                .attr("action", "session-initiate")
                .attr("sid", "my-session-123")
                .build()
        assertEquals("my-session-123", extractSid(element))
        assertEquals("session-initiate", extractAction(element))
    }

    @Test
    fun buildsParticipantMap() {
        val mappings = listOf("stream-1" to "alice@waddle.social", "stream-2" to "bob@waddle.social")
        val element = buildParticipantMap("sid-123", mappings)
        assertEquals("session-info", element.attr("action"))
        assertEquals("sid-123", element.attr("sid"))
    }

    @Test
    fun jingleContentsToSdpRejectsEmptyContents() {
        assertThrows(IllegalArgumentException::class.java) {
            jingleContentsToSdp(emptyList())
        }
    }

    @Test
    fun sdpToJingleContentsRejectsEmptySdp() {
        assertThrows(IllegalArgumentException::class.java) {
            sdpToJingleContents("v=0\r\n", ContentCreator.INITIATOR)
        }
    }

    private fun assertContains(
        sdp: String,
        needle: String,
    ) {
        assertTrue(
            "SDP missing `$needle`. Full SDP:\n$sdp",
            sdp.contains(needle),
        )
    }
}
