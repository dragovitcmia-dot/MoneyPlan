package com.cashflow.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLogicTest {
    private val sha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val url = "https://github.com/Dragovitcmia-dot/MoneyPlan/releases/download/latest-debug/cashflow-debug.apk"

    private fun json(code: String = "42", name: String = "0.2.1", s: String = sha, u: String = url) =
        """{"versionCode": $code, "versionName": "$name", "sha256": "$s", "apkUrl": "$u"}"""

    // ---- parsing ---------------------------------------------------------------------------

    @Test fun parses_the_published_shape() {
        val info = UpdateLogic.parseVersionInfo(json())
        assertEquals(VersionInfo(42, "0.2.1", sha, url), info)
    }

    @Test fun parses_compact_multiline_and_reordered_json() {
        val text = "{\n  \"apkUrl\":\"$url\",\n  \"sha256\":\"$sha\",\"versionName\":\"1.0\",\n\"versionCode\":7\n}"
        val info = UpdateLogic.parseVersionInfo(text)
        assertEquals(7L, info.versionCode)
        assertEquals("1.0", info.versionName)
    }

    @Test fun sha256_is_normalised_to_lower_case() {
        assertEquals(sha, UpdateLogic.parseVersionInfo(json(s = sha.uppercase())).sha256)
    }

    @Test fun escaped_slashes_in_urls_are_accepted() {
        val escaped = url.replace("/", "\\/")
        assertEquals(url, UpdateLogic.parseVersionInfo(json(u = escaped)).apkUrl)
    }

    @Test fun rejects_malformed_documents() {
        val bad = listOf(
            "", "not json", "{}",
            json(code = "\"42\""), // versionCode must be a number
            json(code = "-1"),
            json(s = "abc"), // too short
            json(s = "z".repeat(64)), // not hex
            json(u = "http://example.com/a.apk"), // not https
            json(name = ""),
            """{"versionCode": 1, "versionName": "x", "sha256": "$sha"}""", // no apkUrl
        )
        for (text in bad) {
            assertTrue("should reject: $text", runCatching { UpdateLogic.parseVersionInfo(text) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    // ---- version comparison ----------------------------------------------------------------

    @Test fun newer_means_strictly_greater_version_code() {
        assertTrue(UpdateLogic.isNewer(43, 42))
        assertFalse(UpdateLogic.isNewer(42, 42))
        assertFalse(UpdateLogic.isNewer(41, 42))
        assertTrue(UpdateLogic.isNewer(2, 1))
    }

    // ---- checksums -------------------------------------------------------------------------

    @Test fun sha256_of_known_vectors() {
        assertEquals(sha, UpdateLogic.sha256Hex("abc".toByteArray()))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", UpdateLogic.sha256Hex(ByteArray(0)))
    }

    @Test fun streaming_digest_equals_one_shot_digest_across_buffer_boundaries() {
        val data = ByteArray(8192 * 3 + 123) { (it * 31).toByte() }
        assertEquals(UpdateLogic.sha256Hex(data), UpdateLogic.sha256Hex(data.inputStream()))
    }

    @Test fun checksum_comparison_is_case_insensitive_and_detects_mismatch() {
        assertTrue(UpdateLogic.checksumMatches(sha, sha.uppercase()))
        assertTrue(UpdateLogic.checksumMatches(sha, " $sha "))
        assertFalse(UpdateLogic.checksumMatches(sha, sha.replaceRange(0, 1, "0")))
        assertFalse(UpdateLogic.checksumMatches(UpdateLogic.sha256Hex("abd".toByteArray()), sha))
    }

    // ---- progress --------------------------------------------------------------------------

    @Test fun progress_fraction() {
        assertEquals(0.5f, UpdateLogic.progress(50, 100)!!, 0.0001f)
        assertEquals(1f, UpdateLogic.progress(150, 100)!!, 0.0001f)
        assertEquals(0f, UpdateLogic.progress(0, 100)!!, 0.0001f)
        assertNull(UpdateLogic.progress(10, -1))
        assertNull(UpdateLogic.progress(10, 0))
    }

    @Test fun version_url_points_at_the_latest_debug_release() {
        assertTrue(UpdateLogic.VERSION_URL.startsWith("https://github.com/Dragovitcmia-dot/MoneyPlan/releases/download/latest-debug/"))
        assertTrue(UpdateLogic.VERSION_URL.endsWith("/version.json"))
    }
}
