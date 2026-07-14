package com.tayson.rockflash.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiskImageInspectorTest {
    @Test
    fun `hybrid ISO is safe for raw writing`() {
        val header = ByteArray(0x9000)
        header[510] = 0x55
        header[511] = 0xAA.toByte()
        "CD001".toByteArray().copyInto(header, 0x8001)

        val result = DiskImageInspector.inspect("linux.iso", header.size.toLong(), header)

        assertEquals(DiskImageKind.HYBRID_ISO, result.kind)
        assertTrue(result.canRawWrite)
    }

    @Test
    fun `official Windows style ISO requires media creation`() {
        val header = ByteArray(0x9000)
        "CD001".toByteArray().copyInto(header, 0x8001)

        val result = DiskImageInspector.inspect("Windows_11_x64.iso", header.size.toLong(), header)

        assertEquals(DiskImageKind.WINDOWS_INSTALL_ISO, result.kind)
        assertEquals(MediaSupportLevel.REQUIRES_CONVERSION, result.supportLevel)
        assertFalse(result.canRawWrite)
    }

    @Test
    fun `DMG trailer is detected`() {
        val trailer = ByteArray(512)
        "koly".toByteArray().copyInto(trailer, 0)

        val result = DiskImageInspector.inspect("macos.dmg", 1024L, ByteArray(64), trailer)

        assertEquals(DiskImageKind.APPLE_DMG, result.kind)
        assertFalse(result.canRawWrite)
    }

    @Test
    fun `raw IMG is accepted`() {
        val result = DiskImageInspector.inspect("backup.img", 4096L, ByteArray(4096))

        assertEquals(DiskImageKind.RAW_DISK, result.kind)
        assertTrue(result.canRawWrite)
    }

    @Test
    fun `Android sparse image requires expansion`() {
        val header = byteArrayOf(0x3A, 0xFF.toByte(), 0x26, 0xED.toByte())

        val result = DiskImageInspector.inspect("system.img", 4096L, header)

        assertEquals(DiskImageKind.ANDROID_SPARSE, result.kind)
        assertFalse(result.canRawWrite)
    }
}
