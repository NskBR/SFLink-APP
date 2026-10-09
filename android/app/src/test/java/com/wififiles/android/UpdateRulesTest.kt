package com.wififiles.android

import org.junit.Assert.*
import org.junit.Test

class UpdateRulesTest {
    @Test fun comparesNumericVersionsAndRejectsPrereleases() {
        assertTrue(UpdateRules.newer("v0.10.0", "0.2.9"))
        assertFalse(UpdateRules.newer("0.2.0", "0.2.0"))
        assertFalse(UpdateRules.newer("0.1.9", "0.2.0"))
        assertNull(UpdateRules.version("1.0.0-beta"))
        assertNull(UpdateRules.version("TESTE"))
    }
    @Test fun requiresOfficialApkAndDigest() {
        val name = "SFLink_0.3.0_android.apk"
        val url = "https://github.com/NskBR/SFLink-APP/releases/download/v0.3.0/$name"
        val hash = "sha256:" + "a".repeat(64)
        assertTrue(UpdateRules.validAsset(name, url, 100, hash))
        assertFalse(UpdateRules.validAsset(name, url.replace("NskBR/SFLink-APP", "other/project"), 100, hash))
        assertFalse(UpdateRules.validAsset(name, url, 100, ""))
        assertFalse(UpdateRules.validAsset(name, url.replace("https:", "http:"), 100, hash))
        assertFalse(UpdateRules.validAsset(name, url, UpdateRules.MAX_SIZE + 1, hash))
    }
}
