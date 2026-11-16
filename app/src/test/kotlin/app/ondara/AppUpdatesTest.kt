package app.ondara

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AppUpdatesTest {
    private fun release(tag: String = "v0.2.0", flags: String = "", url: String = "https://github.com/jefer02/ondara/releases/download/$tag/Ondara-${tag.removePrefix("v")}.apk") =
        """{"tag_name":"$tag",$flags"assets":[{"name":"Ondara-${tag.removePrefix("v")}.apk","browser_download_url":"$url"}]}"""

    @Test fun newerStableReleaseOffersOfficialApk() {
        assertEquals("0.2.0", parseAppUpdate(release(), 1000)?.version)
        assertEquals("0.10.0", parseAppUpdate(release("v0.10.0"), 9000)?.version)
    }

    @Test fun installedAndOlderVersionsAreIgnored() {
        assertNull(parseAppUpdate(release(), 2000))
        assertNull(parseAppUpdate(release(), 3000))
    }

    @Test fun unpublishedAndPrereleaseVersionsAreIgnored() {
        assertNull(parseAppUpdate(release(flags = "\"draft\":true,"), 1000))
        assertNull(parseAppUpdate(release(flags = "\"prerelease\":true,"), 1000))
        assertNull(parseAppUpdate(release("v0.3.0-beta"), 1000))
        assertNull(parseAppUpdate(release("v0.1000.0"), 1000))
    }

    @Test fun missingOrUntrustedApkIsIgnored() {
        assertNull(parseAppUpdate("""{"tag_name":"v0.2.0"}""", 1000))
        assertNull(parseAppUpdate(release(url = "https://example.com/app.apk"), 1000))
    }
}
