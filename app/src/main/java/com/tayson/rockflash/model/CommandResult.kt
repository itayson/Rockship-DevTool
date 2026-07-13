package com.tayson.rockflash.model

data class CommandResult(
    val success: Boolean,
    val exitCode: Int,
    val output: String,
    val durationMs: Long,
)
