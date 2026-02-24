package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.model.ExistingProjectIndicatorModel
import com.terraformation.backend.accelerator.model.ExistingStandardMetricModel
import com.terraformation.backend.accelerator.model.NewProjectIndicatorModel
import com.terraformation.backend.accelerator.model.NewStandardMetricModel
import com.terraformation.backend.accelerator.model.ProjectIndicatorModel
import com.terraformation.backend.accelerator.model.StandardMetricModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.ProjectIndicatorNotFoundException
import com.terraformation.backend.db.StandardMetricNotFoundException
import com.terraformation.backend.db.accelerator.AutoCalculatedIndicator
import com.terraformation.backend.db.accelerator.CommonIndicatorId
import com.terraformation.backend.db.accelerator.ProjectIndicatorId
import com.terraformation.backend.db.accelerator.tables.references.AUTO_CALCULATED_INDICATORS
import com.terraformation.backend.db.accelerator.tables.references.COMMON_INDICATORS
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_INDICATORS
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
  fun fetchOneStandardMetric(metricId: CommonIndicatorId): ExistingStandardMetricModel {
    requirePermissions { readProjectReportConfigs() }

    return fetchStandardMetrics(COMMON_INDICATORS.ID.eq(metricId)).firstOrNull()
        ?: throw StandardMetricNotFoundException(metricId)
  }

  fun fetchAllStandardMetrics(): List<ExistingStandardMetricModel> {
    requirePermissions { readProjectReportConfigs() }

    return fetchStandardMetrics(DSL.trueCondition())
  }

  fun createStandardMetric(model: NewStandardMetricModel): CommonIndicatorId {
    requirePermissions { manageProjectReportConfigs() }

    return with(COMMON_INDICATORS) {
      dslContext
          .insertInto(this)
          .set(NAME, model.name)
          .set(DESCRIPTION, model.description)
          .set(CATEGORY_ID, model.component)
          .set(LEVEL_ID, model.type)
          .set(REF_ID, model.reference)
          .set(IS_PUBLISHABLE, model.isPublishable)
          .set(UNIT, model.unit)
          .returning(ID)
          .fetchOne(ID)!!
    }
  }

  fun updateStandardMetric(
      metricId: CommonIndicatorId,
      updateFunc: (ExistingStandardMetricModel) -> ExistingStandardMetricModel,
  ) {
    requirePermissions { manageProjectReportConfigs() }

    val existing = fetchOneStandardMetric(metricId)
    val new = updateFunc(existing)

    with(COMMON_INDICATORS) {
      dslContext
          .update(this)
          .set(NAME, new.name)
          .set(DESCRIPTION, new.description)
          .set(CATEGORY_ID, new.component)
          .set(LEVEL_ID, new.type)
          .set(REF_ID, new.reference)
          .set(IS_PUBLISHABLE, new.isPublishable)
          .set(UNIT, new.unit)
          .where(ID.eq(metricId))
          .execute()
    }
  }

  private fun fetchStandardMetrics(condition: Condition): List<ExistingStandardMetricModel> {
    return dslContext
        .selectFrom(COMMON_INDICATORS)
        .where(condition)
        .orderBy(COMMON_INDICATORS.REF_ID, COMMON_INDICATORS.ID)
        .fetch { StandardMetricModel.of(it) }
  }

  fun fetchOneProjectIndicator(indicatorId: ProjectIndicatorId): ExistingProjectIndicatorModel {
    requirePermissions { readProjectReportConfigs() }

    return fetchProjectIndicators(PROJECT_INDICATORS.ID.eq(indicatorId)).firstOrNull()
        ?: throw ProjectIndicatorNotFoundException(indicatorId)
  }

  fun fetchProjectIndicatorsForProject(projectId: ProjectId): List<ExistingProjectIndicatorModel> {
    requirePermissions { readProjectReportConfigs() }

    return fetchProjectIndicators(PROJECT_INDICATORS.PROJECT_ID.eq(projectId))
  }

  fun createProjectIndicator(model: NewProjectIndicatorModel): ProjectIndicatorId {
    requirePermissions { manageProjectReportConfigs() }

    return with(PROJECT_INDICATORS) {
      dslContext
          .insertInto(this)
          .set(PROJECT_ID, model.projectId)
          .set(NAME, model.name)
          .set(DESCRIPTION, model.description)
          .set(CATEGORY_ID, model.component)
          .set(LEVEL_ID, model.type)
          .set(REF_ID, model.reference)
          .set(IS_PUBLISHABLE, model.isPublishable)
          .set(UNIT, model.unit)
          .returning(ID)
          .fetchOne(ID)!!
    }
  }

  fun updateProjectIndicator(
      indicatorId: ProjectIndicatorId,
      updateFunc: (ExistingProjectIndicatorModel) -> ExistingProjectIndicatorModel,
  ) {
    requirePermissions { manageProjectReportConfigs() }

    val existing = fetchOneProjectIndicator(indicatorId)
    val new = updateFunc(existing)

    // Cannot update projectId of a metric
    with(PROJECT_INDICATORS) {
      dslContext
          .update(this)
          .set(NAME, new.name)
          .set(DESCRIPTION, new.description)
          .set(CATEGORY_ID, new.component)
          .set(LEVEL_ID, new.type)
          .set(REF_ID, new.reference)
          .set(IS_PUBLISHABLE, new.isPublishable)
          .set(UNIT, new.unit)
          .where(ID.eq(indicatorId))
          .execute()
    }
  }

  private fun fetchProjectIndicators(condition: Condition): List<ExistingProjectIndicatorModel> {
    return dslContext
        .selectFrom(PROJECT_INDICATORS)
        .where(condition)
        .orderBy(PROJECT_INDICATORS.REF_ID, PROJECT_INDICATORS.ID)
        .fetch { ProjectIndicatorModel.of(it) }
  }

  fun fetchSystemMetrics(): List<AutoCalculatedIndicator> {
    requirePermissions { readProjectReportConfigs() }

    return with(AUTO_CALCULATED_INDICATORS) {
      dslContext.select(ID).from(AUTO_CALCULATED_INDICATORS).orderBy(REF_ID).fetch {
        it[ID.asNonNullable()]
      }
    }
  }
}
