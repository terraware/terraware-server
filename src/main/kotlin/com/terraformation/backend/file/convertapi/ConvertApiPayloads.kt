package com.terraformation.backend.file.convertapi

import com.fasterxml.jackson.annotation.JsonFormat

@JsonFormat(with = [JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES])
class ConvertApiFilePayload(
    // File data is base64-encoded in the response; Jackson automatically decodes it.
    val fileData: ByteArray,
)

@JsonFormat(with = [JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES])
class ConvertApiSuccessResponse(
    val files: List<ConvertApiFilePayload>,
)

@JsonFormat(with = [JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES])
class ConvertApiErrorResponse(
    val code: Int,
    val message: String?,
)
