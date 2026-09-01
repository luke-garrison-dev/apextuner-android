package com.apextuner.app

data class AppLaunchRequest(
    val destination: String,
    val quickScan: Boolean,
    val token: Long,
)
