package com.terraformation.backend.splat.sqs

import java.net.URI

data class SplatterRequestFileLocation(
    val bucket: String,
    val key: String,
)

data class SplatterRequestMessage(
    val jobId: String,
    val responseQueueUrl: URI,
    val input: SplatterRequestFileLocation,
    val output: SplatterRequestFileLocation,
    val args: List<String>? = null,
)
