package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.MetricComponent
import com.terraformation.backend.db.accelerator.MetricType
import com.terraformation.backend.db.accelerator.StandardMetricId
import com.terraformation.backend.db.accelerator.tables.references.STANDARD_METRICS
import org.jooq.Record

data class StandardMetricModel<ID : StandardMetricId?>(
    val id: ID,
    val name: String,
    val description: String? = null,
    val component: MetricComponent,
    val type: MetricType,
    val reference: Int,
    val subReference: Int? = null,
    val subSubReference: Int? = null,
) {
  companion object {
    fun of(record: Record): ExistingStandardMetricModel {
      return with(STANDARD_METRICS) {
        ExistingStandardMetricModel(
            id = record[ID]!!,
            name = record[NAME]!!,
            description = record[DESCRIPTION],
            component = record[COMPONENT_ID]!!,
            type = record[TYPE_ID]!!,
            reference = record[REFERENCE]!!,
            subReference = record[SUB_REFERENCE],
            subSubReference = record[SUB_SUB_REFERENCE],
        )
      }
    }
  }
}

typealias ExistingStandardMetricModel = StandardMetricModel<StandardMetricId>

typealias NewStandardMetricModel = StandardMetricModel<Nothing?>
