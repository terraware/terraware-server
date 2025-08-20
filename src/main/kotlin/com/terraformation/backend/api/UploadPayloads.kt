package com.terraformation.backend.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UploadProblemType
import com.terraformation.backend.db.default_schema.UploadStatus
import com.terraformation.backend.file.model.UploadModel
import com.terraformation.backend.file.model.UploadProblemModel
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema

// Request and response payloads for file upload endpoints that follow our typical pattern of
// upload -> validation -> possibly ask the user what to do about duplicates -> update database.

data class UploadFileResponsePayload(
    @Schema(description = "ID of uploaded file. This may be used to poll for the file's status.")
    val id: UploadId
) : SuccessResponsePayload

@JsonInclude(JsonInclude.Include.NON_NULL)
data class UploadProblemPayload(
    @Schema(
        description =
            "Name of the field with the problem. Absent if the problem isn't specific to a " +
                "single field."
    )
    val fieldName: String?,
    @Schema(
        description = "Human-readable description of the problem.",
    )
    val message: String?,
    @Schema(description = "Position (row number) of the record with the problem.")
    val position: Int?,
    val type: UploadProblemType,
    @Schema(
        description =
            "The value that caused the problem. Absent if the problem wasn't caused by a " +
                "specific field value."
    )
    val value: String?,
) {
  constructor(
      model: UploadProblemModel
  ) : this(model.field, model.message, model.position, model.type, model.value)
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class GetUploadStatusDetailsPayload(
    val id: UploadId,
    val status: UploadStatus,
    @ArraySchema(
        schema =
            Schema(
                description =
                    "List of errors in the file. Errors prevent the file from being processed; " +
                        "the file needs to be modified to resolve them."
            )
    )
    val errors: List<UploadProblemPayload>?,
    @ArraySchema(
        schema =
            Schema(
                description =
                    "List of conditions that might cause the user to want to cancel the upload " +
                        "but that can be automatically resolved if desired."
            )
    )
    val warnings: List<UploadProblemPayload>?,
) {
  constructor(
      model: UploadModel
  ) : this(
      model.id,
      model.status,
      model.errors.map { UploadProblemPayload(it) }.ifEmpty { null },
      model.warnings.map { UploadProblemPayload(it) }.ifEmpty { null },
  )

  @get:Schema(
      description =
          "True if the server is finished processing the file, either successfully or not."
  )
  val finished: Boolean
    get() = status.finished
}

data class GetUploadStatusResponsePayload(val details: GetUploadStatusDetailsPayload) :
    SuccessResponsePayload {
  constructor(model: UploadModel) : this(GetUploadStatusDetailsPayload(model))
}

data class ResolveUploadRequestPayload(
    @Schema(
        description =
            "If true, the data for entries that already exist will be overwritten with the " +
                "values in the uploaded file. If false, only entries that don't already exist " +
                "will be imported."
    )
    val overwriteExisting: Boolean
)
