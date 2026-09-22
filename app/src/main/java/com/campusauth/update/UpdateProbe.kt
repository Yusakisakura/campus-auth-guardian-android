package com.campusauth.update

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub probing for update detection — plain github.com pages only,
 * deliberately no api.github.com (works without tokens, no API rate limit).
 */

private const val REPO = "Yusakisakura/campus-auth-guardian-android"
private const val LATEST_URL = "https://github.com/$REPO/releases/latest"
private const val ATOM_URL = "https://github.com/$REPO/releases.atom"
private const val UA = "campus-auth-guardian-android"

internal fun releaseUrl(tag: String): String = "https://github.com/$REPO/releases/tag/$tag"

/**
 * Resolve the latest stable release tag by following the HTTP redirect of
 * /releases/latest (GitHub answers 302 → /releases/tag/<tag>).
 *
 * @return tag like "v1.2.0", or null when the repo has no stable release yet.
 * @throws IOException on network problems or an unexpected response
 *         (a bare 200 usually means a captive portal intercepting requests).
 */
internal fun fetchLatestTag(): String? {
    var url = LATEST_URL
    repeat(3) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 5000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", UA)

            when (val code = conn.responseCode) {
                404 -> return null
                200 -> throw IOException("网络可能需要认证")
                in 300..399 -> {
                    val loc = conn.getHeaderField("Location")
                        ?: throw IOException("重定向缺少 Location")
                    if (loc.contains("/releases/tag/")) {
                        val tag = loc.substringAfter("/releases/tag/")
                            .substringBefore('?').substringBefore('#').trimEnd('/')
                        if (tag.isEmpty()) throw IOException("无法解析版本标签")
                        return tag
                    }
                    url = if (loc.startsWith("http")) loc else "https://github.com$loc"
                }
                else -> throw IOException("HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }
    throw IOException("重定向次数过多")
}

/** Best-effort plain-text release notes for [tag] from releases.atom. Never throws. */
internal fun fetchNotes(tag: String): String = try {
    val conn = URL(ATOM_URL).openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = 5000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", UA)
        if (conn.responseCode == 200) {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(conn.inputStream, "UTF-8")
            parseAtomNotes(parser, tag)
        } else {
            ""
        }
    } finally {
        conn.disconnect()
    }
} catch (_: Exception) {
    ""
}

/** Find the atom <entry> linking to /releases/tag/[tag] and read its <content>. */
private fun parseAtomNotes(parser: XmlPullParser, tag: String): String {
    var inEntry = false
    var matched = false
    var inContent = false
    val content = StringBuilder()

    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> when (parser.name) {
                "entry" -> {
                    inEntry = true; matched = false; content.setLength(0)
                }
                "link" -> if (inEntry && !matched && hrefOf(parser).contains("/releases/tag/$tag")) {
                    matched = true
                }
                "content" -> if (inEntry && matched) inContent = true
            }
            XmlPullParser.TEXT -> if (inContent) parser.text?.let { content.append(it) }
            XmlPullParser.END_TAG -> when (parser.name) {
                "content" -> inContent = false
                "entry" -> {
                    if (matched) return htmlToPlainText(content.toString())
                    inEntry = false
                }
            }
        }
        event = parser.next()
    }
    return ""
}

private fun hrefOf(parser: XmlPullParser): String {
    for (i in 0 until parser.attributeCount) {
        if (parser.getAttributeName(i) == "href") return parser.getAttributeValue(i)
    }
    return ""
}

/** Strip HTML markup to a short plain-text blurb for the update banner. */
private fun htmlToPlainText(html: String): String {
    val text = html
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</p\\s*>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]*>"), "")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex(" *\\n *"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
    return if (text.length > 240) text.take(240).trimEnd() + "…" else text
}
