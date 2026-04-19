package social.waddle.android.xmpp

import social.waddle.android.data.model.FeatureStatus
import social.waddle.android.data.model.XepFeature

object XmppFeatureRegistry {
    val supported: List<XepFeature> =
        listOf(
            XepFeature("RFC 6120", "Core stream and SASL", FeatureStatus.Active),
            XepFeature("RFC 6121", "IM basics", FeatureStatus.Active),
            XepFeature("XEP-0030", "Service discovery", FeatureStatus.Active),
            XepFeature("XEP-0045", "Multi-user chat", FeatureStatus.Active),
            XepFeature("XEP-0085", "Chat states", FeatureStatus.Active),
            XepFeature("XEP-0092", "Software version", FeatureStatus.Active),
            XepFeature("XEP-0115", "Entity capabilities", FeatureStatus.Active),
            XepFeature("XEP-0184", "Delivery receipts", FeatureStatus.Active),
            XepFeature("XEP-0198", "Stream management", FeatureStatus.Active),
            XepFeature("XEP-0199", "XMPP ping", FeatureStatus.Active),
            XepFeature("XEP-0201", "Message threads", FeatureStatus.Active),
            XepFeature("XEP-0203", "Delayed delivery", FeatureStatus.Active),
            XepFeature("XEP-0215", "External service discovery", FeatureStatus.Active),
            XepFeature("XEP-0280", "Message carbons", FeatureStatus.Active),
            XepFeature("XEP-0297", "Stanza forwarding", FeatureStatus.Active),
            XepFeature("XEP-0300", "Cryptographic hash functions", FeatureStatus.Active),
            XepFeature("XEP-0308", "Last message correction", FeatureStatus.Active),
            XepFeature("XEP-0313", "Message archive management", FeatureStatus.Active),
            XepFeature("XEP-0317", "Hats (role badges)", FeatureStatus.Active),
            XepFeature("XEP-0333", "Chat markers", FeatureStatus.Active),
            XepFeature("XEP-0334", "Message processing hints", FeatureStatus.Active),
            XepFeature("XEP-0352", "Client state indication", FeatureStatus.AdapterReady),
            XepFeature("XEP-0359", "Unique and stable stanza IDs", FeatureStatus.Active),
            XepFeature("XEP-0363", "HTTP file upload", FeatureStatus.Active),
            XepFeature("XEP-0372", "References and mentions", FeatureStatus.Active),
            XepFeature("XEP-0393", "Message styling", FeatureStatus.Active),
            XepFeature("XEP-0394", "Message markup", FeatureStatus.AdapterReady),
            XepFeature("XEP-0424", "Message retraction", FeatureStatus.Active),
            XepFeature("XEP-0425", "Moderated message retraction", FeatureStatus.AdapterReady),
            XepFeature("XEP-0444", "Message reactions", FeatureStatus.Active),
            XepFeature("XEP-0446", "File metadata", FeatureStatus.Active),
            XepFeature("XEP-0447", "Stateless file sharing", FeatureStatus.Active),
            XepFeature("XEP-0449", "Stickers", FeatureStatus.AdapterReady),
            XepFeature("XEP-0461", "Message replies", FeatureStatus.Active),
            XepFeature("XEP-0482", "Call invites", FeatureStatus.Active),
            XepFeature("XEP-0486", "MUC avatars", FeatureStatus.AdapterReady),
            XepFeature("XEP-0490", "Message displayed synchronization", FeatureStatus.Active),
            XepFeature("XEP-0502", "MUC activity indicator", FeatureStatus.Active),
            XepFeature("XEP-0508", "Forums", FeatureStatus.AdapterReady),
            XepFeature("XEP-0513", "Explicit mentions", FeatureStatus.Active),
        )
}
