package org.southtyrol.transit.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatesTest {
    @Test fun versionsCompareNumerically() {
        assertTrue(Versions.isNewer("v0.10.0", "0.9.3"))
        assertTrue(Versions.isNewer("1.0", "0.99.99"))
        assertTrue(Versions.isNewer("0.1.1", "0.1.0"))
        assertFalse(Versions.isNewer("v0.1.0", "0.1.0"))
        assertFalse(Versions.isNewer("0.1", "0.1.0"))
        assertFalse(Versions.isNewer("0.0.9", "0.1.0"))
    }

    @Test fun preReleaseRanksBelowRelease() {
        assertTrue(Versions.isNewer("1.0.0", "1.0.0-beta.2"))
        assertFalse(Versions.isNewer("1.0.0-rc.1", "1.0.0"))
        assertTrue(Versions.isNewer("1.0.0-rc.1", "0.9.0"))
    }

    private fun release(tag: String, assets: String, draft: Boolean = false, prerelease: Boolean = false) = """
        {"tag_name":"$tag","name":"Release","body":"- Faster map\n- Fixes","html_url":"https://github.com/o/r/releases/tag/$tag",
         "draft":$draft,"prerelease":$prerelease,"assets":[$assets],"author":{"login":"x"}}
    """.trimIndent()

    private fun asset(name: String) = """{"name":"$name","browser_download_url":"https://github.com/o/r/releases/download/x/$name","size":123,"content_type":"application/vnd.android.package-archive"}"""

    @Test fun picksReleaseApkOverDebug() {
        val (r, hasApk) = ReleaseParser.parse(release("v0.2.0", asset("SouthTyrolTransit-0.2.0-debug.apk") + "," + asset("SouthTyrolTransit-0.2.0-release.apk") + "," + asset("notes.txt")))
        assertTrue(hasApk)
        assertEquals("0.2.0", r!!.version)
        assertTrue(r.apkUrl.endsWith("-release.apk"))
        assertEquals(123, r.apkSize)
        assertEquals("- Faster map\n- Fixes", r.notes)
    }

    @Test fun releaseWithoutApkIsReported() {
        val (r, hasApk) = ReleaseParser.parse(release("v0.2.0", asset("source.zip")))
        assertNull(r)
        assertFalse(hasApk)
    }

    @Test fun draftsAndPreReleasesAreIgnored() {
        assertNull(ReleaseParser.parse(release("v0.3.0", asset("a.apk"), draft = true)).first)
        assertNull(ReleaseParser.parse(release("v0.3.0", asset("a.apk"), prerelease = true)).first)
    }

    @Test fun previewSuffixesCompareNumerically() {
        assertTrue(Versions.isNewer("0.2.0-preview.10", "0.2.0-preview.9"))
        assertTrue(Versions.isNewer("0.2.0", "0.2.0-preview.10"))
        assertTrue(Versions.isNewer("0.2.0-preview.1", "0.1.3"))
        assertFalse(Versions.isNewer("0.1.3", "0.2.0-preview.1"))
    }

    private fun list(vararg releases: String) = "[" + releases.joinToString(",") + "]"

    @Test fun previewChannelTakesTheNewestOfAnyKind() {
        val body = list(
            release("v0.2.0-preview.2", asset("SouthTyrolTransit-0.2.0-preview.2.apk"), prerelease = true),
            release("v0.1.4", asset("SouthTyrolTransit-0.1.4-release.apk")),
            release("v0.3.0-preview.1", asset("x.apk"), draft = true, prerelease = true),
        )
        val (preview, _) = ReleaseParser.parseList(body, allowPreview = true)
        assertEquals("0.2.0-preview.2", preview!!.version)
        assertTrue(preview.preview)
        assertTrue(preview.apkUrl.endsWith("0.2.0-preview.2.apk"))
        val (stable, _) = ReleaseParser.parseList(body, allowPreview = false)
        assertEquals("0.1.4", stable!!.version)
        assertFalse(stable.preview)
    }

    @Test fun stableReleaseNewerThanPreviewWinsOnPreviewChannel() {
        val body = list(
            release("v0.2.0", asset("SouthTyrolTransit-0.2.0-release.apk")),
            release("v0.2.0-preview.3", asset("SouthTyrolTransit-0.2.0-preview.3.apk"), prerelease = true),
        )
        assertEquals("0.2.0", ReleaseParser.parseList(body, allowPreview = true).first!!.version)
    }
}
