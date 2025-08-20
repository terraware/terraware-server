package com.terraformation.backend.accelerator.api

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import com.terraformation.backend.accelerator.db.ReportStore
import com.terraformation.backend.db.accelerator.ProjectMetricId
import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.db.accelerator.StandardMetricId
import com.terraformation.backend.db.accelerator.SystemMetric
import com.terraformation.backend.db.default_schema.ProjectId
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping
import io.swagger.v3.oas.annotations.media.Schema

data class ReportMetricTargetPayload(
    val reportId: ReportId,
    val target: Int?,
)

@JsonSubTypes(
    JsonSubTypes.Type(name = "project", value = UpdateProjectMetricTargetsPayload::class),
    JsonSubTypes.Type(name = "standard", value = UpdateStandardMetricTargetsPayload::class),
    JsonSubTypes.Type(name = "system", value = UpdateSystemMetricTargetsPayload::class),
)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@Schema(
    discriminatorMapping =
        [
            DiscriminatorMapping(
                value = "project",
                schema = UpdateProjectMetricTargetsPayload::class,
            ),
            DiscriminatorMapping(
                value = "standard",
                schema = UpdateStandardMetricTargetsPayload::class,
            ),
            DiscriminatorMapping(
                value = "system",
                schema = UpdateSystemMetricTargetsPayload::class,
            ),
        ]
)
sealed interface UpdateMetricTargetsPayload {
  val targets: List<ReportMetricTargetPayload>

  fun updateMetricTargets(store: ReportStore, projectId: ProjectId, updateSubmitted: Boolean)
}

@JsonTypeName("project")
data class UpdateProjectMetricTargetsPayload(
    val metricId: ProjectMetricId,
    override val targets: List<ReportMetricTargetPayload>,
) : UpdateMetricTargetsPayload {
  override fun updateMetricTargets(
      store: ReportStore,
      projectId: ProjectId,
      updateSubmitted: Boolean,
  ) {
    store.updateProjectMetricTargets(
        projectId = projectId,
        metricId = metricId,
        targets = targets.associate { it.reportId to it.target },
        updateSubmitted = updateSubmitted,
    )
  }
}

@JsonTypeName("standard")
data class UpdateStandardMetricTargetsPayload(
    val metricId: StandardMetricId,
    override val targets: List<ReportMetricTargetPayload>,
) : UpdateMetricTargetsPayload {
  override fun updateMetricTargets(
      store: ReportStore,
      projectId: ProjectId,
      updateSubmitted: Boolean,
  ) {
    store.updateStandardMetricTargets(
        projectId = projectId,
        metricId = metricId,
        targets = targets.associate { it.reportId to it.target },
        updateSubmitted = updateSubmitted,
    )
  }
}

@JsonTypeName("system")
data class UpdateSystemMetricTargetsPayload(
    val metric: SystemMetric,
    override val targets: List<ReportMetricTargetPayload>,
) : UpdateMetricTargetsPayload {
  override fun updateMetricTargets(
      store: ReportStore,
      projectId: ProjectId,
      updateSubmitted: Boolean,
  ) {
    store.updateSystemMetricTargets(
        projectId = projectId,
        metric = metric,
        targets = targets.associate { it.reportId to it.target },
        updateSubmitted = updateSubmitted,
    )
  }
}
