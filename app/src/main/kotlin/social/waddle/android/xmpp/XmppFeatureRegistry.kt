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
            XepFeature("XEP-0313", "Message archive management", FeatureStatus.AdapterReady),
            XepFeature("XEP-0198", "Stream management", FeatureStatus.AdapterReady),
            XepFeature("XEP-0352", "Client state indication", FeatureStatus.AdapterReady),
            XepFeature("XEP-0184", "Delivery receipts", FeatureStatus.Active),
            XepFeature("XEP-0333", "Chat markers", FeatureStatus.Active),
            XepFeature("XEP-0085", "Chat states", FeatureStatus.Active),
            XepFeature("XEP-0308", "Last message correction", FeatureStatus.Active),
            XepFeature("XEP-0372", "References and mentions", FeatureStatus.Active),
            XepFeature("XEP-0424", "Message retraction", FeatureStatus.Active),
            XepFeature("XEP-0425", "Moderated message retraction", FeatureStatus.AdapterReady),
            XepFeature("XEP-0444", "Message reactions", FeatureStatus.Active),
            XepFeature("XEP-0461", "Message replies", FeatureStatus.Active),
            XepFeature("XEP-0363", "HTTP file upload", FeatureStatus.AdapterReady),
            XepFeature("XEP-0446", "File metadata", FeatureStatus.AdapterReady),
            XepFeature("XEP-0447", "Stateless file sharing", FeatureStatus.AdapterReady),
            XepFeature("vCard", "Avatar/profile basics", FeatureStatus.AdapterReady),
            XepFeature("XEP-0357", "Push notifications", FeatureStatus.Deferred),
        )
}
