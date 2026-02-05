package com.terraformation.backend.splat.sqs

import java.net.URI

data class SplatterRequestFileLocation(
    val bucket: String,
    val key: String,
)

data class SplatterRequestMessage(
    val args: List<String>? = null,
    val input: SplatterRequestFileLocation,
    val jobId: String,
    val output: SplatterRequestFileLocation,
    val responseQueueUrl: URI,
    val restoreJob: Boolean = false,
)
