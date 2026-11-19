package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.model.CommonIndicatorModel
import com.terraformation.backend.accelerator.model.ExistingCommonIndicatorModel
import com.terraformation.backend.accelerator.model.ExistingProjectIndicatorModel
import com.terraformation.backend.accelerator.model.NewCommonIndicatorModel
import com.terraformation.backend.accelerator.model.NewProjectIndicatorModel
import com.terraformation.backend.accelerator.model.ProjectAutoCalculatedIndicatorModel
import com.terraformation.backend.accelerator.model.ProjectIndicatorModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.CommonIndicatorNotFoundException
import com.terraformation.backend.db.ProjectIndicatorNotFoundException
import com.terraformation.backend.db.accelerator.AutoCalculatedIndicator
import com.terraformation.backend.db.accelerator.CommonIndicatorId
import com.terraformation.backend.db.accelerator.ProjectIndicatorId
import com.terraformation.backend.db.accelerator.tables.references.AUTO_CALCULATED_INDICATORS
import com.terraformation.backend.db.accelerator.tables.references.COMMON_INDICATORS
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_AUTO_CALCULATED_INDICATORS
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_COMMON_INDICATORS
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_INDICATORS
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_REPORT_CONFIGS
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.ProjectId
import jakarta.inject.Named
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL

@Named
class ReportIndicatorStore(
    private val dslContext: DSLContext,
) {
  // all users can access common indicators
  fun fetchOneCommonIndicator(indicatorId: CommonIndicatorId): ExistingCommonIndicatorModel =
      fetchCommonIndicators(COMMON_INDICATORS.ID.eq(indicatorId)).firstOrNull()
          ?: throw CommonIndicatorNotFoundException(indicatorId)

  // all users can access common indicators
  fun fetchAllCommonIndicators(): List<ExistingCommonIndicatorModel> =
      fetchCommonIndicators(DSL.trueCondition())

  fun createCommonIndicator(model: NewCommonIndicatorModel): CommonIndicatorId {
    requirePermissions { manageProjectReportConfigs() }

    return dslContext.transactionResult { _ ->
      val indicatorId =
          with(COMMON_INDICATORS) {
            dslContext
                .insertInto(this)
                .set(ACTIVE, model.active)
                .set(CATEGORY_ID, model.category)
                .set(CLASS_ID, model.classId)
                .set(DESCRIPTION, model.description)
                .set(FREQUENCY_ID, model.frequency)
                .set(IS_PUBLISHABLE, model.isPublishable)
                .set(LEVEL_ID, model.level)
                .set(NAME, model.name)
                .set(NOTES, model.notes)
                .set(PRECISION, model.precision)
                .set(PRIMARY_DATA_SOURCE, model.primaryDataSource)
                .set(REF_ID, model.refId)
                .set(TF_OWNER, model.tfOwner)
                .set(UNIT, model.unit)
                .returning(ID)
                .fetchOne(ID)!!
          }
      with(PROJECT_COMMON_INDICATORS) {
        dslContext
            .insertInto(
                this,
                PROJECT_ID,
                COMMON_INDICATOR_ID,
                REF_ID,
                ACTIVE,
                IS_PUBLISHABLE,
                NOTES,
                PRIMARY_DATA_SOURCE,
            )
            .select(
                DSL.selectDistinct(
                        PROJECT_REPORT_CONFIGS.PROJECT_ID,
                        DSL.value(indicatorId),
                        DSL.value(model.refId),
                        DSL.value(model.active),
                        DSL.value(model.isPublishable),
                        DSL.value(model.notes),
                        DSL.value(model.primaryDataSource),
                    )
                    .from(PROJECT_REPORT_CONFIGS)
            )
            .onConflictDoNothing()
            .execute()
      }
      indicatorId
    }
  }

  fun updateCommonIndicator(
      indicatorId: CommonIndicatorId,
      updateFunc: (ExistingCommonIndicatorModel) -> ExistingCommonIndicatorModel,
  ) {
    requirePermissions { manageProjectReportConfigs() }

    val existing = fetchOneCommonIndicator(indicatorId)
    val new = updateFunc(existing)

    dslContext.transaction { _ ->
      with(COMMON_INDICATORS) {
        dslContext
            .update(this)
            .set(ACTIVE, new.active)
            .set(CATEGORY_ID, new.category)
            .set(CLASS_ID, new.classId)
            .set(DESCRIPTION, new.description)
            .set(FREQUENCY_ID, new.frequency)
            .set(IS_PUBLISHABLE, new.isPublishable)
            .set(LEVEL_ID, new.level)
            .set(NAME, new.name)
            .set(NOTES, new.notes)
            .set(PRECISION, new.precision)
            .set(PRIMARY_DATA_SOURCE, new.primaryDataSource)
            .set(REF_ID, new.refId)
            .set(TF_OWNER, new.tfOwner)
            .set(UNIT, new.unit)
            .where(ID.eq(indicatorId))
            .execute()
      }
      with(PROJECT_COMMON_INDICATORS) {
        dslContext
            .update(this)
            .set(REF_ID, new.refId)
            .set(ACTIVE, new.active)
            .set(IS_PUBLISHABLE, new.isPublishable)
            .set(NOTES, new.notes)
            .set(PRIMARY_DATA_SOURCE, new.primaryDataSource)
            .where(COMMON_INDICATOR_ID.eq(indicatorId))
            .execute()
      }
    }
  }

  private fun fetchCommonIndicators(condition: Condition): List<ExistingCommonIndicatorModel> {
    return dslContext
        .selectFrom(COMMON_INDICATORS)
        .where(condition)
        .orderBy(COMMON_INDICATORS.REF_ID.asc().nullsLast(), COMMON_INDICATORS.ID)
        .fetch { CommonIndicatorModel.of(it) }
  }

  fun fetchCommonIndicatorsForProject(projectId: ProjectId): List<ExistingCommonIndicatorModel> {
    requirePermissions { readProjectIndicators(projectId) }

    return dslContext
        .select(COMMON_INDICATORS.asterisk(), PROJECT_COMMON_INDICATORS.asterisk())
        .from(COMMON_INDICATORS)
        .join(PROJECT_COMMON_INDICATORS)
        .on(PROJECT_COMMON_INDICATORS.COMMON_INDICATOR_ID.eq(COMMON_INDICATORS.ID))
        .where(PROJECT_COMMON_INDICATORS.PROJECT_ID.eq(projectId))
        .orderBy(PROJECT_COMMON_INDICATORS.REF_ID.asc().nullsLast(), COMMON_INDICATORS.ID)
        .fetch { record ->
          CommonIndicatorModel.of(record)
              .copy(
                  active = record[PROJECT_COMMON_INDICATORS.ACTIVE]!!,
                  isPublishable = record[PROJECT_COMMON_INDICATORS.IS_PUBLISHABLE]!!,
                  notes = record[PROJECT_COMMON_INDICATORS.NOTES],
                  primaryDataSource = record[PROJECT_COMMON_INDICATORS.PRIMARY_DATA_SOURCE],
                  refId = record[PROJECT_COMMON_INDICATORS.REF_ID],
              )
        }
  }

  fun updateCommonIndicatorForProject(
      projectId: ProjectId,
      indicatorId: CommonIndicatorId,
      model: ExistingCommonIndicatorModel,
  ) {
    requirePermissions { manageProjectReportConfigs() }

    dslContext.transaction { _ ->
      val rowsUpdated =
          with(PROJECT_COMMON_INDICATORS) {
            dslContext
                .update(this)
                .set(ACTIVE, model.active)
                .set(IS_PUBLISHABLE, model.isPublishable)
                .set(NOTES, model.notes)
                .set(PRIMARY_DATA_SOURCE, model.primaryDataSource)
                .set(REF_ID, model.refId)
                .where(PROJECT_ID.eq(projectId))
                .and(COMMON_INDICATOR_ID.eq(indicatorId))
                .execute()
          }
      if (rowsUpdated == 0) throw CommonIndicatorNotFoundException(indicatorId)

      with(COMMON_INDICATORS) {
        dslContext
            .update(this)
            .set(CATEGORY_ID, model.category)
            .set(CLASS_ID, model.classId)
            .set(DESCRIPTION, model.description)
            .set(FREQUENCY_ID, model.frequency)
            .set(LEVEL_ID, model.level)
            .set(NAME, model.name)
            .set(PRECISION, model.precision)
            .set(TF_OWNER, model.tfOwner)
            .set(UNIT, model.unit)
            .where(ID.eq(indicatorId))
            .execute()
      }
    }
  }

  fun fetchAutoCalculatedIndicatorsForProject(
      projectId: ProjectId
  ): List<ProjectAutoCalculatedIndicatorModel> {
    requirePermissions { readProjectIndicators(projectId) }

    return with(PROJECT_AUTO_CALCULATED_INDICATORS) {
      dslContext
          .selectFrom(this)
          .where(PROJECT_ID.eq(projectId))
          .orderBy(REF_ID.asc().nullsLast(), AUTO_CALCULATED_INDICATOR_ID)
          .fetch { record ->
            ProjectAutoCalculatedIndicatorModel(
                active = record[ACTIVE]!!,
                indicator = record[AUTO_CALCULATED_INDICATOR_ID]!!,
                isPublishable = record[IS_PUBLISHABLE]!!,
                notes = record[NOTES],
                primaryDataSource = record[PRIMARY_DATA_SOURCE],
                refId = record[REF_ID],
            )
          }
    }
  }

  fun updateAutoCalculatedIndicatorForProject(
      projectId: ProjectId,
      indicatorId: AutoCalculatedIndicator,
      active: Boolean,
      isPublishable: Boolean,
      notes: String?,
      refId: String?,
  ) {
    requirePermissions { manageProjectReportConfigs() }

    val rowsUpdated =
        with(PROJECT_AUTO_CALCULATED_INDICATORS) {
          dslContext
              .update(this)
              .set(ACTIVE, active)
              .set(IS_PUBLISHABLE, isPublishable)
              .set(NOTES, notes)
              .set(REF_ID, refId)
              .where(PROJECT_ID.eq(projectId))
              .and(AUTO_CALCULATED_INDICATOR_ID.eq(indicatorId))
              .execute()
        }
    if (rowsUpdated == 0)
        throw ProjectAutoCalculatedIndicatorNotFoundException(projectId, indicatorId)
  }

  fun fetchOneProjectIndicator(indicatorId: ProjectIndicatorId): ExistingProjectIndicatorModel {
    val indicator = fetchProjectIndicators(PROJECT_INDICATORS.ID.eq(indicatorId)).firstOrNull()

    if (indicator == null) {
      throw ProjectIndicatorNotFoundException(indicatorId)
    } else {
      requirePermissions { readProjectIndicators(indicator.projectId) }
      return indicator
    }
  }

  fun fetchProjectIndicatorsForProject(projectId: ProjectId): List<ExistingProjectIndicatorModel> {
    requirePermissions { readProjectIndicators(projectId) }

    return fetchProjectIndicators(PROJECT_INDICATORS.PROJECT_ID.eq(projectId))
  }

  fun createProjectIndicator(model: NewProjectIndicatorModel): ProjectIndicatorId {
    requirePermissions { manageProjectReportConfigs() }

    return with(PROJECT_INDICATORS) {
      dslContext
          .insertInto(this)
          .set(ACTIVE, model.active)
          .set(CATEGORY_ID, model.category)
          .set(CLASS_ID, model.classId)
          .set(DESCRIPTION, model.description)
          .set(FREQUENCY_ID, model.frequency)
          .set(IS_PUBLISHABLE, model.isPublishable)
          .set(LEVEL_ID, model.level)
          .set(NAME, model.name)
          .set(NOTES, model.notes)
          .set(PRECISION, model.precision)
          .set(PRIMARY_DATA_SOURCE, model.primaryDataSource)
          .set(PROJECT_ID, model.projectId)
          .set(REF_ID, model.refId)
          .set(TF_OWNER, model.tfOwner)
          .set(UNIT, model.unit)
          .returning(ID)
          .fetchOne(ID)!!
    }
  }

  fun updateProjectIndicator(
      indicatorId: ProjectIndicatorId,
      projectId: ProjectId? = null,
      updateFunc: (ExistingProjectIndicatorModel) -> ExistingProjectIndicatorModel,
  ) {
    requirePermissions { manageProjectReportConfigs() }

    val existing = fetchOneProjectIndicator(indicatorId)
    if (projectId != null && existing.projectId != projectId) {
      throw ProjectIndicatorNotFoundException(indicatorId)
    }
    val new = updateFunc(existing)

    // Cannot update projectId of an indicator
    with(PROJECT_INDICATORS) {
      dslContext
          .update(this)
          .set(ACTIVE, new.active)
          .set(CATEGORY_ID, new.category)
          .set(CLASS_ID, new.classId)
          .set(DESCRIPTION, new.description)
          .set(FREQUENCY_ID, new.frequency)
          .set(IS_PUBLISHABLE, new.isPublishable)
          .set(LEVEL_ID, new.level)
          .set(NAME, new.name)
          .set(NOTES, new.notes)
          .set(PRECISION, new.precision)
          .set(PRIMARY_DATA_SOURCE, new.primaryDataSource)
          .set(REF_ID, new.refId)
          .set(TF_OWNER, new.tfOwner)
          .set(UNIT, new.unit)
          .where(ID.eq(indicatorId))
          .execute()
    }
  }

  private fun fetchProjectIndicators(condition: Condition): List<ExistingProjectIndicatorModel> {
    return dslContext
        .selectFrom(PROJECT_INDICATORS)
        .where(condition)
        .orderBy(PROJECT_INDICATORS.REF_ID.asc().nullsLast(), PROJECT_INDICATORS.ID)
        .fetch { ProjectIndicatorModel.of(it) }
  }

  // all users can access auto calculated indicators
  fun fetchAutoCalculatedIndicators(): List<AutoCalculatedIndicator> =
      with(AUTO_CALCULATED_INDICATORS) {
        dslContext
            .select(ID)
            .from(AUTO_CALCULATED_INDICATORS)
            .orderBy(REF_ID.asc().nullsLast(), ID)
            .fetch {
              it[ID.asNonNullable()]
            }
      }
}
