package com.tayson.rockflash.usb

import org.junit.Assert.assertEquals
import org.junit.Test

class RockchipModeDetectorTest {
    @Test
    fun `even bcdUSB identifies MaskROM`() {
        assertEquals(RockchipMode.MASKROM, RockchipModeDetector.fromBcdUsb(0x0200))
    }

    @Test
    fun `odd bcdUSB identifies Loader`() {
        assertEquals(RockchipMode.LOADER, RockchipModeDetector.fromBcdUsb(0x0201))
    }
}
