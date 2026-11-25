// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaybackV5SignerTest {
    private fun resource(name: String) = javaClass.getResource("/$name")!!.readText()
    private fun values(json: JSONObject): Map<String, String> =
        json.keys().asSequence().associateWith { json.getString(it) }

    private fun parameters(fixture: JSONObject): Map<String, String> {
        val order = fixture.getJSONArray("valueOrder")
        val fields = fixture.getJSONObject("values")
        return (0 until order.length()).associate { i ->
            val key = order.getString(i)
            key to fields.getString(key)
        }
    }

    @Test fun matchesIndependentDigestVectorsForAll32TimestampRotations() {
        // Unmodified synthetic SDK-oracle vectors from N3urda/hongguoTV 18c37c2.
        val rows = JSONArray(resource("signing-v5-f13.json"))
        val signer = PlaybackV5Signer()
        assertEquals(32, rows.length())
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val result = signer.hashF13(hex(row.getString("query")), hex(row.getString("body")), row.getInt("ts"))
            assertEquals("timestamp class $i", row.getString("digest"), result.copyOf(16).hex())
            assertEquals(20, result.size)
            assertEquals(0x24, result[16].u() and 0x24)
        }
    }

    @Test fun matchesIndependentCarrierVectorsForAllFourTimestampVariants() {
        // Unmodified synthetic SDK-oracle vectors from N3urda/hongguoTV 18c37c2.
        val rows = JSONArray(resource("signing-v5-carrier.json"))
        assertEquals(4, rows.length())
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val result = PlaybackV5Carrier(hex(row.getString("key")), row.getInt("ts"))
                .encrypt(hex(row.getString("source")), hex(row.getString("iv")))
            assertEquals("carrier variant $i", row.getString("encrypted"), result.hex())
        }
    }

    @Test fun matchesIndependentBodylessGetEnvelopesAndPreservesIdentity() {
        // Synthetic vectors use the hongguo-drama JavaScript carrier and the PR #122 envelope.
        // This verifies MD5(empty bytes), not the legacy null body's sixteen zero bytes.
        val fixture = JSONObject(resource("playback-v5-get.json"))
        val params = parameters(fixture)
        val snapshot = params.toMap()
        val started = fixture.getLong("processStarted")
        val signer = PlaybackV5Signer({ started }, { fixture.getDouble("random") },
            fixture.getString("processNonce"), started)
        val cases = fixture.getJSONArray("cases")
        assertEquals(4, cases.length())
        for (i in 0 until cases.length()) {
            val row = cases.getJSONObject(i)
            val signed = signer.signGet(fixture.getString("base"), params, row.getLong("now"))
            assertEquals("final signed query $i", row.getString("url"), signed.url)
            assertEquals("full signature $i", values(row.getJSONObject("headers")), signed.headers)
            assertEquals(setOf("x-khronos", "x-helios", "x-medusa"), signed.headers.keys)
            assertNull(signed.body)
            val url = signed.url.toHttpUrl()
            assertNull(url.queryParameter("ts"))
            params.forEach { (key, value) -> assertEquals(value, url.queryParameter(key)) }
            assertTrue(url.queryParameter("_rticket")!!.toLong() - row.getLong("now") in 0L..1023L)
        }
        assertEquals(snapshot, params)
    }

    @Test fun existingTimestampIsRefreshedWithoutInventingIdentity() {
        val fixture = JSONObject(resource("playback-v5-get.json"))
        val params = parameters(fixture) + ("ts" to "1")
        val now = fixture.getLong("processStarted")
        val signed = PlaybackV5Signer({ now }).signGet(fixture.getString("base"), params)
        assertEquals((now / 1000).toString(), signed.url.toHttpUrl().queryParameter("ts"))
        assertEquals((now / 1000).toString(), signed.headers["x-khronos"])
        for (missing in listOf("device_id", "iid", "cdid")) {
            assertThrows(IllegalArgumentException::class.java) {
                PlaybackV5Signer().signGet(fixture.getString("base"), params - missing, now)
            }
        }
    }

    @Test fun matchesIndependentGzipPostEnvelopesAndReturnsTheExactSignedBody() {
        // The JavaScript reference signs these exact gzip bytes with the same entropy as GET.
        val fixture = JSONObject(resource("playback-v5-post.json"))
        val params = parameters(fixture)
        val snapshot = params.toMap()
        val body = decode64(fixture.getString("bodyBase64"))
        val started = fixture.getLong("processStarted")
        val signer = PlaybackV5Signer({ started }, { fixture.getDouble("random") },
            fixture.getString("processNonce"), started)
        val cases = fixture.getJSONArray("cases")
        assertEquals(4, cases.length())
        for (i in 0 until cases.length()) {
            val row = cases.getJSONObject(i)
            val signed = signer.signPost(fixture.getString("base"), params, body, row.getLong("now"))
            assertEquals("final signed POST query $i", row.getString("url"), signed.url)
            assertEquals("full POST signature $i", values(row.getJSONObject("headers")), signed.headers)
            assertEquals(setOf("x-khronos", "x-helios", "x-medusa", "x-ss-stub"), signed.headers.keys)
            assertArrayEquals(body, signed.body)
            assertEquals(digest("MD5", body).hex().uppercase(), signed.headers["x-ss-stub"])
            assertNull(signed.url.toHttpUrl().queryParameter("ts"))
            params.forEach { (key, value) -> assertEquals(value, signed.url.toHttpUrl().queryParameter(key)) }
        }
        assertEquals(snapshot, params)
    }

    @Test fun postSigningRejectsAPlainOrTruncatedBodyInsteadOfSigningDifferentBytes() {
        val fixture = JSONObject(resource("playback-v5-post.json"))
        val body = decode64(fixture.getString("bodyBase64"))
        for (invalid in listOf(ByteArray(0), fixture.getString("uncompressedBody").toByteArray(), body.copyOf(body.size - 1))) {
            assertThrows(IllegalArgumentException::class.java) {
                PlaybackV5Signer().signPost(fixture.getString("base"), parameters(fixture), invalid,
                    fixture.getLong("processStarted"))
            }
        }
    }

    @Test fun cancelledSigningStopsBeforeEmittingARequest() {
        val fixture = JSONObject(resource("playback-v5-get.json"))
        Thread.currentThread().interrupt()
        try {
            assertThrows(java.io.IOException::class.java) {
                PlaybackV5Signer().signGet(fixture.getString("base"), parameters(fixture),
                    fixture.getLong("processStarted"))
            }
        } finally {
            Thread.interrupted()
        }
    }
}
