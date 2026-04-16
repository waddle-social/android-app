package social.waddle.android.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Parses an HTML document for OpenGraph / Twitter Card / plain meta tags via
 * jsoup. Matches the precedence Slack, Twitter, and most feed readers use:
 * `og:*` wins, then `twitter:*`, then generic `name=description` / the
 * document `<title>`.
 */
internal object OpenGraphParser {
    fun parse(
        html: String,
        baseUri: String = "",
    ): LinkPreview? {
        val doc = Jsoup.parse(html, baseUri)
        val title =
            meta(doc, "og:title")
                ?: meta(doc, "twitter:title")
                ?: doc.title().takeIf { it.isNotBlank() }
        val description =
            meta(doc, "og:description")
                ?: meta(doc, "twitter:description")
                ?: meta(doc, "description")
        val image =
            meta(doc, "og:image:secure_url")
                ?: meta(doc, "og:image")
                ?: meta(doc, "twitter:image")
                ?: meta(doc, "twitter:image:src")
        val siteName = meta(doc, "og:site_name")
        val allEmpty = listOf(title, description, image, siteName).all { it.isNullOrBlank() }
        if (allEmpty) return null
        return LinkPreview(
            url = "",
            title = title?.trim()?.takeIf { it.isNotEmpty() },
            description = description?.trim()?.takeIf { it.isNotEmpty() },
            imageUrl = image?.trim()?.takeIf { it.isNotEmpty() },
            siteName = siteName?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun meta(
        doc: Document,
        name: String,
    ): String? =
        doc
            .selectFirst(
                "meta[property=$name], meta[name=$name], meta[itemprop=$name]",
            )?.attr("content")
            ?.takeIf { it.isNotBlank() }
}
