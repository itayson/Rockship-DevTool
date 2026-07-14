package com.tayson.rockflash.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaWriterStateTest {
    @Test
    fun `write is enabled only for ready target and raw-compatible image`() {
        val target = UsbMediaTargetState(
            deviceId = 7,
            displayName = "USB Disk",
            vidPid = "1234:5678",
            transport = UsbStorageTransport.SCSI_BOT,
            supportLevel = MediaSupportLevel.SUPPORTED,
            reason = "supported",
            permissionGranted = true,
            capacityBytes = 16uL * 1024uL * 1024uL,
            blockSize = 512,
        )
        val inspection = DiskImageInspection(
            kind = DiskImageKind.RAW_DISK,
            supportLevel = MediaSupportLevel.SUPPORTED,
            description = "raw",
            canRawWrite = true,
        )

        val state = MediaWriterState(
            targets = listOf(target),
            selectedDeviceId = 7,
            imageName = "test.img",
            imageSizeBytes = 8L * 1024L * 1024L,
            imageInspection = inspection,
        )

        assertTrue(state.canWrite)
        assertFalse(state.copy(imageSizeBytes = 32L * 1024L * 1024L).canWrite)
        assertFalse(state.copy(selectedDeviceId = null).canWrite)
        assertFalse(state.copy(imageInspection = inspection.copy(canRawWrite = false)).canWrite)
    }
}
