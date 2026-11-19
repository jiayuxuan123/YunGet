package com.yunget.desktop

import com.yunget.app.data.network.SharePlatform
import com.yunget.app.data.network.model.ShareExpire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.path.createTempDirectory

class DesktopBatch2Test {

    @Test
    fun multiAccountStoreSupportsAddSwitchRemarkAndPrimaryPromotion() {
        val dir = createTempDirectory("yunget-multi-account-").toFile()
        try {
            val store = DesktopAccountStore(dir)
            store.saveAccount(
                DesktopAccount(
                    kind = DriveAccountKind.QUARK,
                    credential = "cookie-main-1",
                    nickname = "主号A",
                    id = "acc-a",
                ),
                addAsNew = false,
            )
            store.saveAccount(
                DesktopAccount(
                    kind = DriveAccountKind.QUARK,
                    credential = "cookie-sub-2",
                    nickname = "小号B",
                    id = "acc-b",
                ),
                addAsNew = true,
            )

            val list = store.accountsOf(DriveAccountKind.QUARK)
            assertEquals(2, list.size)
            assertEquals("acc-b", store.get(DriveAccountKind.QUARK)?.id)
            assertEquals("cookie-sub-2", store.credentialOf(DriveAccountKind.QUARK))

            // Remark update & primary switching
            store.updateRemark("acc-a", "主力盘")
            store.setPrimary(DriveAccountKind.QUARK, "acc-a")
            assertEquals("acc-a", store.get(DriveAccountKind.QUARK)?.id)
            assertEquals("主力盘", store.get(DriveAccountKind.QUARK)?.displayLabel)

            // Persistence round-trip
            val reloaded = DesktopAccountStore(dir)
            assertEquals(2, reloaded.accountsOf(DriveAccountKind.QUARK).size)
            assertEquals("acc-a", reloaded.get(DriveAccountKind.QUARK)?.id)
            assertEquals("主力盘", reloaded.get(DriveAccountKind.QUARK)?.remark)

            // Removing current primary promotes remaining account automatically
            reloaded.removeById("acc-a")
            assertEquals(1, reloaded.accountsOf(DriveAccountKind.QUARK).size)
            assertEquals("acc-b", reloaded.get(DriveAccountKind.QUARK)?.id)
            assertTrue(reloaded.get(DriveAccountKind.QUARK)?.isPrimary == true)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun driveAndResolveCapabilitiesCoverBatch2Matrix() {
        val dir = createTempDirectory("yunget-drive-caps-").toFile()
        try {
            val accounts = DesktopAccountStore(dir)
            val drive = DesktopDriveService(accounts)
            val resolve = DesktopResolveService(accounts)

            assertTrue(DriveAccountKind.FEIJIPAN.supportsPersonalDrive)
            assertEquals("0", drive.rootDirId(DriveAccountKind.FEIJIPAN))
            assertFalse(drive.supportsFolderMutation(DriveAccountKind.FEIJIPAN))

            val uploadExpected = setOf(
                DriveAccountKind.QUARK,
                DriveAccountKind.BAIDU,
                DriveAccountKind.UC,
                DriveAccountKind.XUNLEI,
                DriveAccountKind.C139,
                DriveAccountKind.PAN123,
                DriveAccountKind.PAN115,
                DriveAccountKind.ILANZOU,
                DriveAccountKind.GUANGYA,
            )
            uploadExpected.forEach { assertTrue("Upload expected for $it", drive.supportsUpload(it)) }

            val shareExpected = setOf(
                DriveAccountKind.QUARK,
                DriveAccountKind.BAIDU,
                DriveAccountKind.UC,
                DriveAccountKind.XUNLEI,
                DriveAccountKind.C139,
                DriveAccountKind.PAN123,
                DriveAccountKind.PAN115,
                DriveAccountKind.GUANGYA,
                DriveAccountKind.LANZOU,
            )
            shareExpected.forEach { assertTrue("Share expected for $it", drive.supportsCreateShare(it)) }

            val pan115Options = drive.shareExpireOptions(DriveAccountKind.PAN115)
            assertEquals(ShareExpire.PAN115_FOREVER, pan115Options.first().second)
            assertEquals(5, pan115Options.size)

            val savePlatforms = listOf(
                SharePlatform.QUARK,
                SharePlatform.UC,
                SharePlatform.BAIDU,
                SharePlatform.C139,
                SharePlatform.PAN123,
                SharePlatform.PAN115,
                SharePlatform.GUANGYA,
                SharePlatform.ILANZOU,
                SharePlatform.XUNLEI,
            )
            savePlatforms.forEach { assertTrue("Save-to-drive expected for $it", resolve.supportsSaveToDrive(it)) }
            assertFalse(resolve.supportsSaveToDrive(SharePlatform.GITHUB))
            assertNotNull(accounts.allAccounts.value)
        } finally {
            dir.deleteRecursively()
        }
    }
}
