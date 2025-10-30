package com.terraformation.backend.file.api

import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.file.mux.MuxStreamModel

data class GetMuxStreamResponsePayload(
    val playbackId: String,
    val playbackToken: String,
) : SuccessResponsePayload {
  constructor(
      model: MuxStreamModel
  ) : this(playbackId = model.playbackId, playbackToken = model.playbackToken)
}
