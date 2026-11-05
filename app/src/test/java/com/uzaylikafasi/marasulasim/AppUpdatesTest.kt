package com.uzaylikafasi.marasulasim

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppUpdatesTest {
    private fun fixture():JSONObject {
        val version="1.1.0"
        return JSONObject().put("tag_name","v$version").put("body","Sürüm notları").put("assets",JSONArray(listOf("maras-ulasim-v$version-arm64.apk","maras-ulasim-v$version.apk").map {
            JSONObject().put("name",it).put("size",100L).put("browser_download_url","https://github.com/uzaylikafasi/maras-ulasim/releases/download/v$version/$it")
        }))
    }
    @Test fun comparesVersionNumbersNotText() {
        assertTrue(newerVersion("1.10.0","1.9.9")); assertTrue(newerVersion("v1.1.0","1.0.1"))
        assertFalse(newerVersion("1.1.0","1.1.0"));assertFalse(newerVersion("1.0.1","1.1.0"))
        assertFalse(newerVersion("1.1.0-beta","1.0.1"));assertFalse(newerVersion("99999999999.1.0","1.0.1"))
    }
    @Test fun matchesAbiAndSkipsInstalledOrSkippedVersions() {
        assertTrue(parseRelease(fixture(),"1.0.1",listOf("arm64-v8a","armeabi-v7a"),null)!!.url.endsWith("-arm64.apk"))
        assertTrue(parseRelease(fixture(),"1.0.1",listOf("armeabi-v7a"),null)!!.url.endsWith("v1.1.0.apk"))
        assertNull(parseRelease(fixture(),"1.1.0",listOf("arm64-v8a"),null))
        assertNull(parseRelease(fixture(),"1.0.1",listOf("arm64-v8a"),"1.1.0"))
        assertNull(parseRelease(fixture().put("prerelease",true),"1.0.1",emptyList(),null))
        assertNull(parseRelease(fixture().put("draft",true),"1.0.1",emptyList(),null))
    }
    @Test fun rejectsUnexpectedUrlsAndMissingOrOversizedApks() {
        val bad=fixture(); bad.getJSONArray("assets").getJSONObject(0).put("browser_download_url","https://example.org/evil.apk")
        assertNull(parseRelease(bad,"1.0.1",listOf("arm64-v8a"),null))
        assertNull(parseRelease(fixture().put("assets",JSONArray()),"1.0.1",emptyList(),null))
        val large=fixture();large.getJSONArray("assets").getJSONObject(0).put("size",100_000_001L)
        assertNull(parseRelease(large,"1.0.1",listOf("arm64-v8a"),null))
    }
    @Test fun dailyGateIncludesOfflineAttemptsAndClockRollback() {
        assertTrue(updateCheckDue(0,100))
        assertFalse(updateCheckDue(100,100+UPDATE_CHECK_INTERVAL_MS-1))
        assertTrue(updateCheckDue(100,100+UPDATE_CHECK_INTERVAL_MS))
        assertTrue(updateCheckDue(100,99))
    }
    @Test fun arm64FallsBackToUniversalAndPreservesReleaseNotes() {
        val o=fixture();o.getJSONArray("assets").remove(0)
        val release=parseRelease(o,"1.0.1",listOf("arm64-v8a"),null)!!
        assertTrue(release.url.endsWith("v1.1.0.apk"));assertEquals("Sürüm notları",release.notes)
    }
}
