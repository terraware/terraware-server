@file:Suppress("PropertyName") // Properties use snake_case to match Mux API

package com.terraformation.backend.file.mux

import com.terraformation.backend.db.default_schema.AssetStatus
import com.terraformation.backend.db.default_schema.FileId
import java.net.URI
import java.time.Instant

/*
 * Mux API and webhook payloads. These are abbreviated; they only contain the fields we care about.
 * The actual payloads include a lot of additional information.
 */

@Suppress("EnumEntryName")
enum class MuxApiStatus(val assetStatus: AssetStatus) {
  preparing(AssetStatus.Preparing),
  ready(AssetStatus.Ready),
  errored(AssetStatus.Errored),
}

data class MuxVideoAssetMetaPayload(
    val external_id: String,
)

data class MuxVideoAssetData(
    val errors: ErrorPayload?,
    val id: String,
    val meta: MuxVideoAssetMetaPayload?,
    val playback_ids: List<PlaybackIdPayload>?,
    val status: MuxApiStatus,
) {
  val errorMessage: String?
    get() = errors?.let { "${it.type}: ${it.messages.joinToString("; ")}" }

  val playbackId: String?
    get() = playback_ids?.firstOrNull()?.id

  val fileId: FileId?
    get() = meta?.external_id?.let { FileId(it) }

  data class PlaybackIdPayload(val id: String)

  data class ErrorPayload(val type: String, val messages: List<String>)
}

data class MuxCreateVideoAssetResponsePayload(val data: MuxVideoAssetData)

data class MuxCreateVideoAssetRequestPayload(
    val inputs: List<Input>,
    val meta: MuxVideoAssetMetaPayload,
    val test: Boolean,
) {
  constructor(
      fileId: FileId,
      test: Boolean,
      url: URI,
  ) : this(
      inputs = listOf(Input(url)),
      meta = MuxVideoAssetMetaPayload("$fileId"),
      test = test,
  )

  @Suppress("unused")
  val playback_policies
    get() = listOf("signed")

  data class Input(val url: URI)
}

data class MuxWebhookRequestPayload(
    val created_at: Instant,
    val data: MuxVideoAssetData,
    val id: String,
    val type: String,
)
