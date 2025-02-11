package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.model.ExistingStandardMetricModel
import com.terraformation.backend.accelerator.model.NewStandardMetricModel
import com.terraformation.backend.accelerator.model.StandardMetricModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.StandardMetricNotFoundException
import com.terraformation.backend.db.accelerator.StandardMetricId
import com.terraformation.backend.db.accelerator.tables.references.STANDARD_METRICS
import jakarta.inject.Named
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL

@Named
class ReportMetricStore(
    private val dslContext: DSLContext,
) {
  fun fetchOneStandardMetric(metricId: StandardMetricId): ExistingStandardMetricModel {
    requirePermissions { manageProjectReportConfigs() }

    return fetchStandardMetrics(STANDARD_METRICS.ID.eq(metricId)).firstOrNull()
        ?: throw StandardMetricNotFoundException(metricId)
  }

  fun fetchAllStandardMetrics(): List<ExistingStandardMetricModel> {
    requirePermissions { manageProjectReportConfigs() }

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
          .returning(ID)
          .fetchOne(ID)!!
    }
  }

  fun updateStandardMetric(
      metricId: StandardMetricId,
      updateFunc: (ExistingStandardMetricModel) -> ExistingStandardMetricModel
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
          .where(ID.eq(metricId))
          .execute()
    }
  }

  private fun fetchStandardMetrics(condition: Condition): List<ExistingStandardMetricModel> {
    return dslContext
        .selectFrom(STANDARD_METRICS)
        .where(condition)
        .orderBy(STANDARD_METRICS.REFERENCE)
        .fetch { StandardMetricModel.of(it) }
  }
}
