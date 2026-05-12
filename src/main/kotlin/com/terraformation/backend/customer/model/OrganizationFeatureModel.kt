package com.terraformation.backend.customer.model

import com.terraformation.backend.db.default_schema.ProjectId
import org.jooq.Field
import org.jooq.Record

/** List of features that can be conditionally available to an organization */
enum class OrganizationFeature {
  Applications,
  Deliverables,
  Modules,
  Reports,
  SeedFundReports,
  VirtualWalkthrough,
}

data class OrganizationFeatureModel(
    val feature: OrganizationFeature,
    val enabled: Boolean,
    val projectIds: Set<ProjectId> = emptySet(),
) {
  companion object {
    fun ofProjectIds(
        feature: OrganizationFeature,
        record: Record,
        projectIdsField: Field<Set<ProjectId>>,
    ): OrganizationFeatureModel {
      val projectIds = record[projectIdsField]
      return OrganizationFeatureModel(
          feature = feature,
          enabled = projectIds.isNotEmpty(),
          projectIds = projectIds,
      )
    }

    fun of(
        feature: OrganizationFeature,
        record: Record,
        enabledField: Field<Boolean>,
    ): OrganizationFeatureModel {
      return OrganizationFeatureModel(
          feature = feature,
          enabled = record[enabledField],
      )
    }
  }
}
