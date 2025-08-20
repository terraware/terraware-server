package com.terraformation.backend.report.api

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SeedFundReportId
import com.terraformation.backend.db.default_schema.SeedFundReportStatus
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.report.model.LatestSeedFundReportBodyModel
import com.terraformation.backend.report.model.SeedFundReportBodyModelV1
import com.terraformation.backend.report.model.SeedFundReportModel
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

/**
 * Top-level version-independent interface for all report payload classes that contain fields the
 * client can edit.
 *
 * This is implemented by both the GET and PUT payload classes to guarantee that the two use the
 * same names and types for editable fields. The GET payload classes can also include other fields.
 *
 * See the package-level documentation (Package.md) for an overview of the class hierarchy.
 */
interface EditableReportFields

/** Version-independent metadata fields that are included in all GET response payloads. */
interface ReportMetadataFields {
  val id: SeedFundReportId
  val lockedByName: String?
  val lockedByUserId: UserId?
  val lockedTime: Instant?
  val modifiedByName: String?
  val modifiedByUserId: UserId?
  val modifiedTime: Instant?
  val projectId: ProjectId?
  val projectName: String?
  val quarter: Int
  val status: SeedFundReportStatus
  val submittedByName: String?
  val submittedByUserId: UserId?
  val submittedTime: Instant?
  val year: Int
}

/**
 * Base interface for GetReportResponsePayload.report. The mapping between version numbers and
 * concrete payload classes is defined here.
 *
 * The number of implementations of this interface will grow over time as we introduce new report
 * versions.
 *
 * We always return in-progress reports using the latest payload version, upgrading it from an older
 * version if needed. But once a report is submitted, we want it to be frozen, since version
 * migrations might be lossy. We need to keep the old payload classes around to support fetching
 * historical reports.
 */
@JsonSubTypes(
    JsonSubTypes.Type(GetReportPayloadV1::class),
)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "version")
@Schema(
    discriminatorMapping =
        [
            DiscriminatorMapping(value = "1", schema = GetReportPayloadV1::class),
        ]
)
sealed interface GetReportPayload : ReportMetadataFields {
  companion object {
    fun of(model: SeedFundReportModel, getFullName: (UserId) -> String?): GetReportPayload {
      return when (model.body) {
        is SeedFundReportBodyModelV1 -> GetReportPayloadV1(model.metadata, model.body, getFullName)
      }
    }
  }
}

/**
 * Base interface for PutReportRequestPayload.report. The mapping between version numbers and
 * concrete payload classes is defined here.
 *
 * Generally speaking, there won't be too many implementations of this interface, because we always
 * migrate in-progress reports to the latest version. But just after we roll out a new report
 * version, there might be users who were in the middle of editing a report. When they hit the save
 * button, we'll get a PUT request that uses the previous version. We need to keep older versions
 * around for long enough to handle those cases, but not after that.
 */
@JsonSubTypes(
    JsonSubTypes.Type(name = "1", value = PutReportPayloadV1::class),
)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "version")
@Schema(
    discriminatorMapping =
        [
            DiscriminatorMapping(value = "1", schema = PutReportPayloadV1::class),
        ]
)
sealed interface PutReportPayload : EditableReportFields {
  /**
   * Overwrites the fields in a report body with the values from this payload. Note that
   * [LatestSeedFundReportBodyModel] is a type alias. When we introduce a new report version, we
   * update [LatestSeedFundReportBodyModel] to point to its model class, and the implementations of
   * this method need to be updated accordingly.
   */
  fun copyTo(model: LatestSeedFundReportBodyModel): LatestSeedFundReportBodyModel
}
