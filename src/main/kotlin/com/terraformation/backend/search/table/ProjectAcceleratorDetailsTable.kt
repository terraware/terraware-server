package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_ACCELERATOR_DETAILS
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

class ProjectAcceleratorDetailsTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = PROJECT_ACCELERATOR_DETAILS.PROJECT_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          projects.asSingleValueSublist(
              "project", PROJECT_ACCELERATOR_DETAILS.PROJECT_ID.eq(PROJECTS.ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      with(PROJECT_ACCELERATOR_DETAILS) {
        listOf(
            bigDecimalField("applicationReforestableLand", APPLICATION_REFORESTABLE_LAND),
            bigDecimalField("confirmedReforestableLand", CONFIRMED_REFORESTABLE_LAND),
            textField("dealDescription", DEAL_DESCRIPTION),
            nonLocalizableEnumField("dealStage", DEAL_STAGE_ID),
            textField("failureRisk", FAILURE_RISK),
            textField("investmentThesis", INVESTMENT_THESIS),
            bigDecimalField("maxCarbonAccumulation", MAX_CARBON_ACCUMULATION),
            bigDecimalField("minCarbonAccumulation", MIN_CARBON_ACCUMULATION),
            integerField("numCommunities", NUM_COMMUNITIES),
            integerField("numNativeSpecies", NUM_NATIVE_SPECIES),
            bigDecimalField("perHectareBudget", PER_HECTARE_BUDGET),
            nonLocalizableEnumField("pipeline", PIPELINE_ID),
            bigDecimalField("totalExpansionPotential", TOTAL_EXPANSION_POTENTIAL),
            textField("whatNeedsToBeTrue", WHAT_NEEDS_TO_BE_TRUE),
        )
      }

  override fun conditionForVisibility(): Condition {
    return if (currentUser().canReadAllDeliverables()) {
      DSL.trueCondition()
    } else {
      DSL.falseCondition()
    }
  }

  override fun conditionForOrganization(organizationId: OrganizationId): Condition {
    return PROJECT_ACCELERATOR_DETAILS.projects.ORGANIZATION_ID.eq(organizationId)
  }
}
