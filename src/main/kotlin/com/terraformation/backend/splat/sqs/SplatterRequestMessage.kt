package com.terraformation.backend.splat.sqs

import java.net.URI

data class SplatterRequestFileLocation(
    val bucket: String,
    val key: String,
)

data class SplatterRequestMessage(
    val abortAfter: String? = null,
    val birdnetOutput: SplatterRequestFileLocation? = null,
    val input: SplatterRequestFileLocation,
    val jobId: String,
    val output: SplatterRequestFileLocation,
    val responseQueueUrl: URI,
    val restartAt: String? = null,
    val restoreJob: Boolean = false,
    val stepArgs: Map<String, List<String>>? = null,
)
