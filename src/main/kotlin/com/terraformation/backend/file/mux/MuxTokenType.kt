package com.terraformation.backend.file.mux

enum class MuxTokenType(
    /**
     * Value to use as the audience when generating JWTs to include in Mux URLs. Mux requires
     * different audience values for fetching different kinds of data.
     */
    val audience: String
) {
  Thumbnail("t"),
  Video("v"),
}
