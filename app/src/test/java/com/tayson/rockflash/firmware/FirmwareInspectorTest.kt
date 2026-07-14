package com.tayson.rockflash.firmware

import com.tayson.rockflash.core.FirmwareKind
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class FirmwareInspectorTest {
    @Test
    fun `raw img without Rockchip signature is flashable disk image`() {
        val inspection = FirmwareInspector.inspect("armbian.img") {
            ByteArrayInputStream(byteArrayOf(0x00, 0x00, 0x00, 0x00))
        }

        assertEquals(FirmwareKind.RAW_DISK_IMAGE, inspection.kind)
    }

    @Test
    fun `RKFW container is blocked from raw flashing`() {
        val inspection = FirmwareInspector.inspect("vendor.img") {
            ByteArrayInputStream("RKFW".toByteArray())
        }

        assertEquals(FirmwareKind.ROCKCHIP_CONTAINER, inspection.kind)
        assertEquals("RKFW", inspection.signature)
    }

    @Test
    fun `RKAF container is blocked from raw flashing`() {
        val inspection = FirmwareInspector.inspect("update.img") {
            ByteArrayInputStream("RKAF".toByteArray())
        }

        assertEquals(FirmwareKind.ROCKCHIP_CONTAINER, inspection.kind)
    }

    @Test
    fun `parameter txt bypasses binary signature inspection`() {
        val inspection = FirmwareInspector.inspect("parameter.txt") {
            error("stream must not be opened")
        }

        assertEquals(FirmwareKind.PARAMETER, inspection.kind)
    }
}
