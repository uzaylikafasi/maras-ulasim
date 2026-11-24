package com.uzaylikafasi.marasulasim

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class LegacyAndroidTest {
    private fun release(vararg names: String): JSONObject = JSONObject().put("tag_name","v1.2.1").put("body","n").put("assets",JSONArray(names.map {
        JSONObject().put("name",it).put("size",100L).put("browser_download_url","https://github.com/uzaylikafasi/maras-ulasim/releases/download/v1.2.1/$it")
    }))
    @Test fun legacyBuildOnlyTakesTheAndroid6Apk() {
        val all = release("maras-ulasim-v1.2.1-arm64.apk","maras-ulasim-v1.2.1.apk","maras-ulasim-v1.2.1-android6.apk")
        assertTrue(parseRelease(all,"1.2.0",listOf("arm64-v8a"),null,legacy=true)!!.url.endsWith("-android6.apk"))
        // Android 6 APK'sı yoksa normal APK'ya düşme: Android 8+ ister, eski telefona kurulmaz.
        assertNull(parseRelease(release("maras-ulasim-v1.2.1-arm64.apk","maras-ulasim-v1.2.1.apk"),"1.2.0",listOf("arm64-v8a"),null,legacy=true))
    }
    @Test fun normalBuildNeverTakesTheAndroid6Apk() {
        val all = release("maras-ulasim-v1.2.1-android6.apk","maras-ulasim-v1.2.1-arm64.apk","maras-ulasim-v1.2.1.apk")
        assertTrue(parseRelease(all,"1.2.0",listOf("arm64-v8a"),null,legacy=false)!!.url.endsWith("-arm64.apk"))
        assertTrue(parseRelease(all,"1.2.0",listOf("armeabi-v7a"),null,legacy=false)!!.url.endsWith("v1.2.1.apk"))
        assertNull(parseRelease(release("maras-ulasim-v1.2.1-android6.apk"),"1.2.0",listOf("arm64-v8a"),null,legacy=false))
    }
    @Test fun extraRootsAreTheKnownPublicCas() {
        fun sha(pem: String) = MessageDigest.getInstance("SHA-256").digest(LegacyTls.parse(pem).encoded).joinToString("") { "%02x".format(it) }
        val known = setOf(
            "96bcec06264976f37460779acf28c5a7cfe8a3c0aae11a8ffcee05c0bddf08c6", // ISRG Root X1
            "4ff460d54b9c86dabfbcfc5712e0400d2bed3fbc4d4fbdaa86e06adcd2a9ad7a", // USERTrust ECC
            "e793c9b02fd8aa13e21c31228accb08119643b749c898964b1746d46c3d4cbd2") // USERTrust RSA
        assertEquals(known, LegacyTls.extraRootsPem.map(::sha).toSet())
    }
}
