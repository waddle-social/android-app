package social.waddle.android.xmpp

import org.jivesoftware.smack.packet.StandardExtensionElement

object XmppExtensions {
    /**
     * XEP-0359 `<origin-id id="UUID" xmlns="urn:xmpp:sid:0"/>`. Lets us
     * carry the sender-chosen stanza id as an explicit extension, so
     * even if a MUC rewrites the `<message id=…>` attribute, peers can
     * still recover the original id for reply / thread cross-references.
     */
    fun originId(id: String): StandardExtensionElement =
        StandardExtensionElement
            .builder("origin-id", "urn:xmpp:sid:0")
            .addAttribute("id", id)
            .build()

    fun markable(): StandardExtensionElement = StandardExtensionElement.builder("markable", "urn:xmpp:chat-markers:0").build()

    fun storeHint(): StandardExtensionElement = StandardExtensionElement.builder("store", "urn:xmpp:hints").build()

    fun noStoreHint(): StandardExtensionElement = StandardExtensionElement.builder("no-store", "urn:xmpp:hints").build()

    fun displayed(messageId: String): StandardExtensionElement =
        StandardExtensionElement
            .builder("displayed", "urn:xmpp:chat-markers:0")
            .addAttribute("id", messageId)
            .build()

    fun correction(messageId: String): StandardExtensionElement =
        StandardExtensionElement
            .builder("replace", "urn:xmpp:message-correct:0")
            .addAttribute("id", messageId)
            .build()

    fun referenceMention(uri: String): StandardExtensionElement =
        StandardExtensionElement
            .builder("reference", "urn:xmpp:reference:0")
            .addAttribute("type", "mention")
            .addAttribute("uri", if (uri.startsWith("xmpp:")) uri else "xmpp:$uri")
            .build()

    fun referenceMention(
        uri: String,
        begin: Int,
        end: Int,
    ): StandardExtensionElement =
        StandardExtensionElement
            .builder("reference", "urn:xmpp:reference:0")
            .addAttribute("type", "mention")
            .addAttribute("uri", if (uri.startsWith("xmpp:")) uri else "xmpp:$uri")
            .addAttribute("begin", begin.toString())
            .addAttribute("end", end.toString())
            .build()

    fun explicitMentions(types: List<String>): StandardExtensionElement {
        val builder = StandardExtensionElement.builder("mentions", "urn:xmpp:emn:0")
        types.distinct().forEach { type ->
            builder.addElement(
                StandardExtensionElement
                    .builder("mention", "urn:xmpp:emn:0")
                    .addAttribute("type", type)
                    .build(),
            )
        }
        return builder.build()
    }

    fun reply(
        messageId: String,
        toJid: String? = null,
    ): StandardExtensionElement {
        val builder =
            StandardExtensionElement
                .builder("reply", "urn:xmpp:reply:0")
                .addAttribute("id", messageId)
        toJid?.takeIf(String::isNotBlank)?.let { builder.addAttribute("to", it) }
        return builder.build()
    }

    fun fallback(
        forNamespace: String,
        start: Int,
        end: Int,
    ): StandardExtensionElement =
        StandardExtensionElement
            .builder("fallback", "urn:xmpp:fallback:0")
            .addAttribute("for", forNamespace)
            .addElement(
                StandardExtensionElement
                    .builder("body", "urn:xmpp:fallback:0")
                    .addAttribute("start", start.toString())
                    .addAttribute("end", end.toString())
                    .build(),
            ).build()

    fun retract(messageId: String): StandardExtensionElement =
        StandardExtensionElement
            .builder("retract", "urn:xmpp:message-retract:1")
            .addAttribute("id", messageId)
            .build()

    fun reaction(
        messageId: String,
        emojis: List<String>,
    ): StandardExtensionElement {
        val builder = StandardExtensionElement.builder("reactions", "urn:xmpp:reactions:0")
        builder.addAttribute("id", messageId)
        emojis
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .forEach { emoji -> builder.addElement("reaction", emoji) }
        return builder.build()
    }

    fun fileSharing(
        url: String,
        name: String? = null,
        mediaType: String? = null,
        size: Long? = null,
        description: String? = null,
        disposition: String = "inline",
    ): StandardExtensionElement {
        val fileBuilder = StandardExtensionElement.builder("file", "urn:xmpp:file:metadata:0")
        name?.takeIf(String::isNotBlank)?.let { fileBuilder.addElement("name", it) }
        mediaType?.takeIf(String::isNotBlank)?.let { fileBuilder.addElement("media-type", it) }
        size?.let { fileBuilder.addElement("size", it.toString()) }
        description?.takeIf(String::isNotBlank)?.let { fileBuilder.addElement("desc", it) }
        val sources =
            StandardExtensionElement
                .builder("sources", "urn:xmpp:sfs:0")
                .addElement(
                    StandardExtensionElement
                        .builder("url-data", "http://jabber.org/protocol/url-data")
                        .addAttribute("target", url)
                        .build(),
                ).build()
        return StandardExtensionElement
            .builder("file-sharing", "urn:xmpp:sfs:0")
            .addAttribute("disposition", disposition)
            .addElement(fileBuilder.build())
            .addElement(sources)
            .build()
    }

    /** XEP-0508 Forums: `<thread-create xmlns='urn:xmpp:forums:0' title='…'/>`. */
    fun forumThreadCreate(title: String): StandardExtensionElement =
        StandardExtensionElement
            .builder("thread-create", "urn:xmpp:forums:0")
            .addAttribute("title", title)
            .build()

    /** XEP-0508 Forums: `<thread-reply xmlns='urn:xmpp:forums:0' thread-id='…'/>`. */
    fun forumThreadReply(threadId: String): StandardExtensionElement =
        StandardExtensionElement
            .builder("thread-reply", "urn:xmpp:forums:0")
            .addAttribute("thread-id", threadId)
            .build()

    /** XEP-0449 Stickers: `<sticker xmlns='urn:xmpp:stickers:0' pack='…'/>`. */
    fun sticker(pack: String?): StandardExtensionElement {
        val builder = StandardExtensionElement.builder("sticker", "urn:xmpp:stickers:0")
        pack?.takeIf(String::isNotBlank)?.let { builder.addAttribute("pack", it) }
        return builder.build()
    }

    /** XEP-0201 Message Thread: `<thread parent='…'>ID</thread>` (parent optional). */
    fun thread(
        threadId: String,
        parentThreadId: String? = null,
    ): StandardExtensionElement {
        val builder =
            StandardExtensionElement
                .builder("thread", "jabber:client")
                .setText(threadId)
        parentThreadId?.takeIf(String::isNotBlank)?.let { builder.addAttribute("parent", it) }
        return builder.build()
    }

    fun callInvite(
        inviteId: String,
        externalUri: String?,
        video: Boolean,
        muji: Boolean = true,
    ): StandardExtensionElement {
        val builder =
            StandardExtensionElement
                .builder("invite", "urn:xmpp:call-invites:0")
                .addAttribute("id", inviteId)
                .addElement(StandardExtensionElement.builder("muji", "urn:xmpp:call-invites:0").setText(muji.toString()).build())
        externalUri?.takeIf(String::isNotBlank)?.let { uri ->
            builder.addElement(
                StandardExtensionElement
                    .builder("external", "urn:xmpp:call-invites:0")
                    .addAttribute("uri", uri)
                    .build(),
            )
        }
        val label = if (video) "Video call" else "Audio call"
        return builder
            .addElement(
                StandardExtensionElement
                    .builder("meeting", "urn:xmpp:http:online-meetings:invite:0")
                    .addAttribute("type", if (muji) "muji" else "dm")
                    .addAttribute("desc", label)
                    .apply {
                        externalUri?.takeIf(String::isNotBlank)?.let { addAttribute("url", it) }
                    }.build(),
            ).build()
    }
}
