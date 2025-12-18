package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.model.ExistingProjectMetricModel
import com.terraformation.backend.accelerator.model.ExistingStandardMetricModel
import com.terraformation.backend.accelerator.model.NewProjectMetricModel
import com.terraformation.backend.accelerator.model.NewStandardMetricModel
import com.terraformation.backend.accelerator.model.ProjectMetricModel
import com.terraformation.backend.accelerator.model.StandardMetricModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.ProjectMetricNotFoundException
import com.terraformation.backend.db.StandardMetricNotFoundException
import com.terraformation.backend.db.accelerator.ProjectMetricId
import com.terraformation.backend.db.accelerator.StandardMetricId
import com.terraformation.backend.db.accelerator.SystemMetric
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_METRICS
import com.terraformation.backend.db.accelerator.tables.references.STANDARD_METRICS
import com.terraformation.backend.db.accelerator.tables.references.SYSTEM_METRICS
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.ProjectId
import jakarta.inject.Named
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL

@Named
class ReportMetricStore(
    private val dslContext: DSLContext,
) {
  fun fetchOneStandardMetric(metricId: StandardMetricId): ExistingStandardMetricModel {
    requirePermissions { readProjectReportConfigs() }

    return fetchStandardMetrics(STANDARD_METRICS.ID.eq(metricId)).firstOrNull()
        ?: throw StandardMetricNotFoundException(metricId)
  }

  fun fetchAllStandardMetrics(): List<ExistingStandardMetricModel> {
    requirePermissions { readProjectReportConfigs() }

    return fetchStandardMetrics(DSL.trueCondition())
  }

  fun createStandardMetric(model: NewStandardMetricModel): StandardMetricId {
    requirePermissions { manageProjectReportConfigs() }

    return with(STANDARD_METRICS) {
      dslContext
          .insertInto(this)
          .set(NAME, model.name)
          .set(DESCRIPTION, model.description)
          .set(COMPONENT_ID, model.component)
          .set(TYPE_ID, model.type)
          .set(REFERENCE, model.reference)
          .set(IS_PUBLISHABLE, model.isPublishable)
          .set(UNIT, model.unit)
          .returning(ID)
          .fetchOne(ID)!!
    }
  }

  fun updateStandardMetric(
      metricId: StandardMetricId,
      updateFunc: (ExistingStandardMetricModel) -> ExistingStandardMetricModel,
  ) {
    requirePermissions { manageProjectReportConfigs() }

    val existing = fetchOneStandardMetric(metricId)
    val new = updateFunc(existing)

    with(STANDARD_METRICS) {
      dslContext
          .update(this)
          .set(NAME, new.name)
          .set(DESCRIPTION, new.description)
          .set(COMPONENT_ID, new.component)
          .set(TYPE_ID, new.type)
          .set(REFERENCE, new.reference)
          .set(IS_PUBLISHABLE, new.isPublishable)
          .set(UNIT, new.unit)
          .where(ID.eq(metricId))
          .execute()
    }
  }

  private fun fetchStandardMetrics(condition: Condition): List<ExistingStandardMetricModel> {
    return dslContext
        .selectFrom(STANDARD_METRICS)
        .where(condition)
        .orderBy(STANDARD_METRICS.REFERENCE, STANDARD_METRICS.ID)
        .fetch { StandardMetricModel.of(it) }
  }

  fun fetchOneProjectMetric(metricId: ProjectMetricId): ExistingProjectMetricModel {
    requirePermissions { readProjectReportConfigs() }

    return fetchProjectMetrics(PROJECT_METRICS.ID.eq(metricId)).firstOrNull()
        ?: throw ProjectMetricNotFoundException(metricId)
  }

  fun fetchProjectMetricsForProject(projectId: ProjectId): List<ExistingProjectMetricModel> {
    requirePermissions { readProjectReportConfigs() }

    return fetchProjectMetrics(PROJECT_METRICS.PROJECT_ID.eq(projectId))
  }

  fun createProjectMetric(model: NewProjectMetricModel): ProjectMetricId {
    requirePermissions { manageProjectReportConfigs() }

    return with(PROJECT_METRICS) {
      dslContext
          .insertInto(this)
          .set(PROJECT_ID, model.projectId)
          .set(NAME, model.name)
          .set(DESCRIPTION, model.description)
          .set(COMPONENT_ID, model.component)
          .set(TYPE_ID, model.type)
          .set(REFERENCE, model.reference)
          .set(IS_PUBLISHABLE, model.isPublishable)
          .set(UNIT, model.unit)
          .returning(ID)
          .fetchOne(ID)!!
    }
  }

  fun updateProjectMetric(
      metricId: ProjectMetricId,
      updateFunc: (ExistingProjectMetricModel) -> ExistingProjectMetricModel,
  ) {
    requirePermissions { manageProjectReportConfigs() }

    val existing = fetchOneProjectMetric(metricId)
    val new = updateFunc(existing)

    // Cannot update projectId of a metric
    with(PROJECT_METRICS) {
      dslContext
          .update(this)
          .set(NAME, new.name)
          .set(DESCRIPTION, new.description)
          .set(COMPONENT_ID, new.component)
          .set(TYPE_ID, new.type)
          .set(REFERENCE, new.reference)
          .set(IS_PUBLISHABLE, new.isPublishable)
          .set(UNIT, new.unit)
          .where(ID.eq(metricId))
          .execute()
    }
  }

  private fun fetchProjectMetrics(condition: Condition): List<ExistingProjectMetricModel> {
    return dslContext
        .selectFrom(PROJECT_METRICS)
        .where(condition)
        .orderBy(PROJECT_METRICS.REFERENCE, PROJECT_METRICS.ID)
        .fetch { ProjectMetricModel.of(it) }
  }

  fun fetchSystemMetrics(): List<SystemMetric> {
    requirePermissions { readProjectReportConfigs() }

    return with(SYSTEM_METRICS) {
      dslContext.select(ID).from(SYSTEM_METRICS).orderBy(REFERENCE).fetch { it[ID.asNonNullable()] }
    }
  }
}
