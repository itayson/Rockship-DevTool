package com.tayson.rockflash.nativebridge

object NativeBridge {
    init {
        System.loadLibrary("rockflash")
    }

    external fun backendVersion(): String
    external fun openUsbFileDescriptor(fileDescriptor: Int): Boolean
    external fun describeOpenDescriptor(): String
    external fun closeUsbFileDescriptor()
}
