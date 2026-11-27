package com.busnotice.commute

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 检查更新：语义化版本比较、测试版渠道的 Release 选取与新旧判断 */
class UpdaterVersionTest {
    private fun v(s: String) = SemVer.parse(s)!!

    @Test fun parsesTagsAndVersionNames() {
        assertEquals(SemVer(1, 2, 3), v("v1.2.3"))
        assertEquals(SemVer(1, 2, 3), v("1.2.3"))
        assertEquals(SemVer(0, 5, 0, listOf("beta", "1")), v("v0.5.0-beta.1"))
        assertEquals(SemVer(0, 5, 0, listOf("rc", "2")), v("0.5.0-rc.2+build.7"))
        assertEquals("0.5.0-alpha.1", v("v0.5.0-alpha.1").toString())
        assertTrue(v("0.5.0-alpha.1").isPrerelease)
        assertFalse(v("0.5.0").isPrerelease)
    }

    @Test fun rejectsMalformedVersions() {
        for (s in listOf("", "1.2", "1.2.3.4", "v1.2.x", "01.2.3", "1.2.3-", "1.2.3-beta..1", "latest", "99999999999.0.0")) {
            assertNull(s, SemVer.parse(s))
        }
    }

    @Test fun prereleaseOrderFollowsSemVer() {
        val ordered = listOf(
            "0.4.9", "0.5.0-alpha", "0.5.0-alpha.1", "0.5.0-alpha.2", "0.5.0-alpha.10", "0.5.0-alpha.beta",
            "0.5.0-beta", "0.5.0-beta.2", "0.5.0-beta.11", "0.5.0-rc.1", "0.5.0", "0.5.1-alpha.1", "0.5.1", "0.10.0", "1.0.0",
        ).map(::v)
        for (i in ordered.indices) for (j in ordered.indices) {
            val expected = i.compareTo(j).coerceIn(-1, 1)
            assertEquals("${ordered[i]} vs ${ordered[j]}", expected, ordered[i].compareTo(ordered[j]).coerceIn(-1, 1))
        }
    }

    @Test fun buildMetadataIsIgnored() {
        assertEquals(0, v("1.0.0+a").compareTo(v("1.0.0+b")))
        assertEquals(0, v("1.0.0-rc.1+a").compareTo(v("1.0.0-rc.1")))
    }

    @Test fun versionCodeIgnoresPrereleaseSuffix() {
        assertEquals(10203, Updater.codeOf("v1.2.3"))
        assertEquals(10203, Updater.codeOf("v1.2.3-beta.1"))
        assertEquals(500, Updater.codeOf("0.5.0-rc.1"))
        assertNull(Updater.codeOf("nightly"))
    }

    @Test fun versionCodeNeverDecreasesAlongSemVerOrder() {
        val ordered = listOf("0.4.1", "0.5.0-alpha.1", "0.5.0-beta.1", "0.5.0", "0.5.1-rc.1", "0.5.1", "1.0.0").map(::v)
        ordered.zipWithNext { a, b -> assertTrue("$a -> $b", b.code >= a.code) }
    }

    private fun release(tag: String, prerelease: Boolean = false, draft: Boolean = false, apk: Boolean = true) =
        JSONObject()
            .put("tag_name", tag)
            .put("prerelease", prerelease)
            .put("draft", draft)
            .put("body", "notes $tag")
            .put("assets", JSONArray().apply {
                put(JSONObject().put("name", "CommuteCapsule-${tag.removePrefix("v")}-mapping.txt")
                    .put("browser_download_url", "https://example.invalid/$tag/mapping.txt"))
                if (apk) put(JSONObject().put("name", "CommuteCapsule-${tag.removePrefix("v")}.apk")
                    .put("browser_download_url", "https://example.invalid/$tag/app.apk"))
            })

    @Test fun parseReleaseReadsApkAssetAndFlags() {
        val r = Updater.parseRelease(release("v0.5.0-beta.1", prerelease = true))!!
        assertEquals("0.5.0-beta.1", r.name)
        assertEquals(500, r.build)
        assertTrue(r.prerelease)
        assertEquals("https://example.invalid/v0.5.0-beta.1/app.apk", r.apkUrl)
        assertNull(Updater.parseRelease(release("v0.5.0", draft = true)))
        assertNull(Updater.parseRelease(release("v0.5.0", apk = false)))
        assertNull(Updater.parseRelease(release("nightly")))
    }

    @Test fun suffixedTagCountsAsPrereleaseEvenWithoutFlag() {
        assertTrue(Updater.parseRelease(release("v0.5.0-rc.1", prerelease = false))!!.prerelease)
    }

    @Test fun pickLatestSkipsDraftsAndHonoursChannel() {
        val list = JSONArray()
            .put(release("v0.6.0-alpha.1", prerelease = true, draft = true))
            .put(release("v0.5.1-beta.1", prerelease = true))
            .put(release("v0.5.0"))
            .put(release("v0.5.0-rc.1", prerelease = true))
            .put(release("v0.4.0"))
        assertEquals("0.5.1-beta.1", Updater.pickLatest(list, includePrerelease = true)!!.name)
        assertEquals("0.5.0", Updater.pickLatest(list, includePrerelease = false)!!.name)
    }

    @Test fun pickLatestPrefersStableOverItsOwnPrerelease() {
        val list = JSONArray()
            .put(release("v0.5.0-rc.2", prerelease = true))
            .put(release("v0.5.0"))
        assertEquals("0.5.0", Updater.pickLatest(list, includePrerelease = true)!!.name)
        assertNull(Updater.pickLatest(JSONArray(), includePrerelease = true))
    }

    @Test fun apiUrlDependsOnChannel() {
        assertTrue(Updater.apiUrl(false).endsWith("/releases/latest"))
        assertTrue(Updater.apiUrl(true).endsWith("/releases?per_page=10"))
    }

    @Test fun newerComparesBySemVer() {
        val beta = Updater.parseRelease(release("v0.5.0-beta.2", prerelease = true))!!
        val stable = Updater.parseRelease(release("v0.5.0"))!!
        assertTrue(Updater.isNewer(beta, "0.4.1", 401))
        assertTrue(Updater.isNewer(beta, "0.5.0-beta.1", 500))
        assertFalse(Updater.isNewer(beta, "0.5.0-beta.2", 500))
        assertFalse(Updater.isNewer(beta, "0.5.0", 500))
        assertTrue(Updater.isNewer(stable, "0.5.0-rc.1", 500))
        assertFalse(Updater.isNewer(stable, "0.5.0", 500))
    }

    @Test fun newerFallsBackToVersionCodeForUnparsableName() {
        val stable = Updater.parseRelease(release("v0.5.0"))!!
        assertTrue(Updater.isNewer(stable, "?", 401))
        assertFalse(Updater.isNewer(stable, "?", 500))
    }
}
