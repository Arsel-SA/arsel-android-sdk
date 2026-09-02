package sa.arsel.core.inapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser's failure mode is silence: a dropped message produces no signal on any surface, server
 * or client. These pin the three-state jsonb reads in particular, because `optString` collapses
 * "absent" and "explicitly null" into `""` and a naive reader would discard perfectly good
 * messages while every dashboard reported the campaign as healthy.
 */
class InAppParserTest {
    @Test
    fun `reads the envelope the success interceptor actually sends`() {
        // The backend spreads `message` and `timestamp` alongside the contract fields; a parser
        // that key-validated the envelope would reject every real response.
        val parsed = requireNotNull(InAppParser.parseCatalogue(catalogue(message()), NOW_MS))

        assertEquals("v1", parsed.version)
        assertEquals(1, parsed.messages.size)
    }

    @Test
    fun `accepts absent, null and present for every optional field`() {
        val nulls =
            message(
                "\"expiresAt\":null,\"buttons\":null,\"variantKey\":null," +
                    "\"trigger\":{\"type\":\"APP_OPEN\",\"eventName\":null,\"properties\":null}",
            )
        val present =
            message(
                "\"expiresAt\":\"2099-01-01T00:00:00.000Z\"," +
                    "\"buttons\":[{\"buttonId\":\"b\",\"label\":\"Go\",\"action\":\"DISMISS\"}]",
            )

        val parsed =
            requireNotNull(
                InAppParser.parseCatalogue(catalogue(message() + "," + nulls + "," + present), NOW_MS),
            )

        assertEquals(3, parsed.messages.size)
    }

    @Test
    fun `defaults a null variantKey rather than dropping the message`() {
        val parsed =
            requireNotNull(InAppParser.parseCatalogue(catalogue(message("\"variantKey\":null")), NOW_MS))

        assertEquals(DEFAULT_VARIANT, parsed.messages.first().variantKey)
    }

    @Test
    fun `treats an absent showCloseButton as shown`() {
        val json =
            "{\"campaignId\":\"c\",\"messageId\":\"m\",\"layout\":\"MODAL\"," +
                "\"content\":{\"headline\":\"H\"}}"

        val parsed = requireNotNull(InAppParser.parseMessage(JSONObject(json)))

        // Only an explicit false hides it; absent must never leave a user with no way out.
        assertTrue(parsed.showCloseButton)
    }

    @Test
    fun `drops a message missing a field it cannot render without`() {
        val json = "{\"campaignId\":\"c\",\"messageId\":\"m\",\"layout\":\"MODAL\",\"content\":{}}"

        assertNull(InAppParser.parseMessage(JSONObject(json)))
    }

    @Test
    fun `drops a layout this build cannot draw`() {
        // Built explicitly rather than through the helper: a second "layout" key would be a
        // duplicate, and org.json keeps the first — the test would then assert nothing.
        val json =
            "{\"campaignId\":\"c\",\"messageId\":\"m\",\"layout\":\"CAROUSEL\"," +
                "\"content\":{\"headline\":\"H\"}}"

        assertNull(InAppParser.parseMessage(JSONObject(json)))
    }

    @Test
    fun `keeps a message whose expiry cannot be parsed`() {
        // Refusing to show a live message is worse than carrying one whose expiry could not be
        // read, so an unparseable date means open-ended rather than expired.
        val parsed =
            requireNotNull(InAppParser.parseMessage(JSONObject(message("\"expiresAt\":\"soon\""))))

        assertNull(parsed.expiresAtMs)
    }

    @Test
    fun `parses an expiry with and without milliseconds`() {
        // The X pattern would need API 24 and this SDK ships to 23, where SimpleDateFormat throws
        // on the pattern itself — every expiry on Android 6 would silently fail to parse.
        val withMillis = message("\"expiresAt\":\"2099-01-01T00:00:00.000Z\"")
        val withoutMillis = message("\"expiresAt\":\"2099-01-01T00:00:00Z\"")

        val a = requireNotNull(InAppParser.parseMessage(JSONObject(withMillis))).expiresAtMs
        val b = requireNotNull(InAppParser.parseMessage(JSONObject(withoutMillis))).expiresAtMs

        assertEquals(a, b)
    }

    @Test
    fun `coerces non-string trigger properties to strings`() {
        // The backend validates properties only with @IsObject(), so a number can arrive.
        val json =
            message(
                "\"trigger\":{\"type\":\"CUSTOM_EVENT\",\"eventName\":\"buy\"," +
                    "\"properties\":{\"n\":2,\"ok\":true}}",
            )

        val parsed = requireNotNull(InAppParser.parseMessage(JSONObject(json)))

        assertEquals("2", parsed.triggerProperties["n"])
        assertEquals("true", parsed.triggerProperties["ok"])
    }

    @Test
    fun `returns null for anything that is not a usable catalogue`() {
        assertNull(InAppParser.parseCatalogue("{\"messages\":[]}", NOW_MS))
        assertNull(InAppParser.parseCatalogue("not json", NOW_MS))
        assertNull(InAppParser.parseCatalogue(null, NOW_MS))
    }

    @Test
    fun `falls back to a sane ttl when the server sends none`() {
        val json = "{\"bundleVersion\":\"v1\",\"messages\":[]}"

        val parsed = requireNotNull(InAppParser.parseCatalogue(json, NOW_MS))

        assertEquals(DEFAULT_TTL_SECONDS, parsed.ttlSeconds)
    }

    @Test
    fun `stamps a beacon timestamp the backend DTO accepts`() {
        val stamped = InAppParser.isoTimestamp(NOW_MS)

        assertTrue("expected a UTC ISO-8601 instant, got $stamped", stamped.endsWith("Z"))
        assertEquals(ISO_LENGTH, stamped.length)
    }

    @Test
    fun `reads a custom-html message`() {
        val json =
            customHtml(
                "\"source\":\"INLINE\",\"html\":\"<p>hi</p>\"," +
                    "\"allowJavaScript\":true,\"overlayStyle\":\"TRANSPARENT\"",
            )

        val custom = requireNotNull(parseCustom(json))

        assertEquals(HTML_SOURCE_INLINE, custom.source)
        assertEquals("<p>hi</p>", custom.html)
        assertTrue(custom.allowJavaScript)
        assertEquals(OVERLAY_TRANSPARENT, custom.overlayStyle)
    }

    @Test
    fun `leaves script off unless it is explicitly on`() {
        // The capability is withheld by default: a creative authored without script must never
        // acquire it from a missing key, a null, or a non-boolean.
        val absent = requireNotNull(parseCustom(customHtml("\"source\":\"INLINE\",\"html\":\"<p>hi</p>\"")))
        val explicitNull =
            requireNotNull(
                parseCustom(
                    customHtml("\"source\":\"INLINE\",\"html\":\"<p>hi</p>\",\"allowJavaScript\":null"),
                ),
            )

        assertTrue(!absent.allowJavaScript)
        assertTrue(!explicitNull.allowJavaScript)
    }

    @Test
    fun `drops a custom-html message whose source has no payload`() {
        // The whole message goes, not just the payload: the author designed markup, and a stray
        // text modal in its place is a worse outcome than the message not appearing.
        assertNull(parseMessage(customHtml("\"source\":\"INLINE\",\"url\":\"https://a.test/x\"")))
        assertNull(parseMessage(customHtml("\"source\":\"URL\",\"html\":\"<p>hi</p>\"")))
        assertNull(parseMessage(customHtml("\"source\":\"MAGIC\",\"html\":\"<p>hi</p>\"")))
    }

    @Test
    fun `carries no custom html on any other layout`() {
        val parsed = requireNotNull(InAppParser.parseMessage(JSONObject(message())))

        assertNull(parsed.customHtml)
    }

    private fun customHtml(fields: String): String =
        "{\"campaignId\":\"c1\",\"messageId\":\"m1\",\"layout\":\"CUSTOM_HTML\"," +
            "\"content\":{\"headline\":\"Hi\",\"showCloseButton\":true}," +
            "\"customHtml\":{" + fields + "}}"

    private fun parseCustom(json: String): InAppCustomHtml? = parseMessage(json)?.customHtml

    private fun parseMessage(json: String): InAppMessage? = InAppParser.parseMessage(JSONObject(json))

    private fun message(extra: String = ""): String {
        val base =
            "\"campaignId\":\"c1\",\"messageId\":\"m1\",\"layout\":\"MODAL\"," +
                "\"content\":{\"headline\":\"Hi\",\"body\":\"There\",\"showCloseButton\":true}"
        return "{" + base + (if (extra.isEmpty()) "" else ",$extra") + "}"
    }

    private fun catalogue(messages: String): String =
        "{\"message\":\"success\",\"timestamp\":\"now\",\"contractVersion\":1," +
            "\"bundleVersion\":\"v1\",\"ttlSeconds\":900,\"messages\":[" + messages + "]}"

    private companion object {
        /** A fixed instant, so nothing here depends on when the suite runs. */
        const val NOW_MS = 1_760_000_000_000L
        const val DEFAULT_TTL_SECONDS = 900

        /** `yyyy-MM-ddTHH:mm:ss.SSSZ` */
        const val ISO_LENGTH = 24
    }
}
