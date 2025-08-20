package com.terraformation.backend.documentproducer.db

import com.terraformation.backend.accelerator.event.VariableValueUpdatedEvent
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.tables.references.DELIVERABLE_VARIABLES
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.docprod.DocumentId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableManifestId
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.db.docprod.VariableValueId
import com.terraformation.backend.db.docprod.VariableWorkflowStatus
import com.terraformation.backend.db.docprod.tables.daos.VariableImageValuesDao
import com.terraformation.backend.db.docprod.tables.daos.VariableLinkValuesDao
import com.terraformation.backend.db.docprod.tables.daos.VariableSectionValuesDao
import com.terraformation.backend.db.docprod.tables.daos.VariableSelectOptionValuesDao
import com.terraformation.backend.db.docprod.tables.daos.VariableValueTableRowsDao
import com.terraformation.backend.db.docprod.tables.daos.VariableValuesDao
import com.terraformation.backend.db.docprod.tables.daos.VariablesDao
import com.terraformation.backend.db.docprod.tables.pojos.VariableImageValuesRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableLinkValuesRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableSectionValuesRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableSelectOptionValuesRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableValueTableRowsRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableValuesRow
import com.terraformation.backend.db.docprod.tables.pojos.VariablesRow
import com.terraformation.backend.db.docprod.tables.references.DOCUMENTS
import com.terraformation.backend.db.docprod.tables.references.VARIABLES
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_IMAGE_VALUES
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_LINK_VALUES
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_MANIFEST_ENTRIES
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_SECTIONS
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_SECTION_DEFAULT_VALUES
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_SECTION_VALUES
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_SELECT_OPTION_VALUES
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_TABLE_COLUMNS
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_VALUES
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_VALUE_TABLE_ROWS
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_WORKFLOW_HISTORY
import com.terraformation.backend.documentproducer.event.CompletedSectionVariableUpdatedEvent
import com.terraformation.backend.documentproducer.model.AppendValueOperation
import com.terraformation.backend.documentproducer.model.BaseVariableValueProperties
import com.terraformation.backend.documentproducer.model.DateValue
import com.terraformation.backend.documentproducer.model.DeleteValueOperation
import com.terraformation.backend.documentproducer.model.DeletedValue
import com.terraformation.backend.documentproducer.model.EmailValue
import com.terraformation.backend.documentproducer.model.ExistingDateValue
import com.terraformation.backend.documentproducer.model.ExistingDeletedValue
import com.terraformation.backend.documentproducer.model.ExistingEmailValue
import com.terraformation.backend.documentproducer.model.ExistingImageValue
import com.terraformation.backend.documentproducer.model.ExistingLinkValue
import com.terraformation.backend.documentproducer.model.ExistingNumberValue
import com.terraformation.backend.documentproducer.model.ExistingSectionValue
import com.terraformation.backend.documentproducer.model.ExistingSelectValue
import com.terraformation.backend.documentproducer.model.ExistingTableValue
import com.terraformation.backend.documentproducer.model.ExistingTextValue
import com.terraformation.backend.documentproducer.model.ExistingValue
import com.terraformation.backend.documentproducer.model.ImageValue
import com.terraformation.backend.documentproducer.model.ImageValueDetails
import com.terraformation.backend.documentproducer.model.LinkValue
import com.terraformation.backend.documentproducer.model.LinkValueDetails
import com.terraformation.backend.documentproducer.model.NewSectionValue
import com.terraformation.backend.documentproducer.model.NewValue
import com.terraformation.backend.documentproducer.model.NumberValue
import com.terraformation.backend.documentproducer.model.ReplaceValuesOperation
import com.terraformation.backend.documentproducer.model.SectionValue
import com.terraformation.backend.documentproducer.model.SectionValueText
import com.terraformation.backend.documentproducer.model.SectionValueVariable
import com.terraformation.backend.documentproducer.model.SelectValue
import com.terraformation.backend.documentproducer.model.TableValue
import com.terraformation.backend.documentproducer.model.TextValue
import com.terraformation.backend.documentproducer.model.UpdateValueOperation
import com.terraformation.backend.documentproducer.model.ValueOperation
import com.terraformation.backend.documentproducer.model.VariableValue
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.log.withMDC
import com.terraformation.backend.ratelimit.RateLimitedEventPublisher
import jakarta.inject.Named
import java.net.URI
import java.time.InstantSource
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL

@Named
class VariableValueStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val eventPublisher: RateLimitedEventPublisher,
    private val variableImageValuesDao: VariableImageValuesDao,
    private val variableLinkValuesDao: VariableLinkValuesDao,
    private val variablesDao: VariablesDao,
    private val variableSectionValuesDao: VariableSectionValuesDao,
    private val variableSelectOptionValuesDao: VariableSelectOptionValuesDao,
    private val variableValuesDao: VariableValuesDao,
    private val variableValueTableRowsDao: VariableValueTableRowsDao,
) {
  private val log = perClassLogger()

  fun fetchBaseProperties(
      valueIds: Collection<VariableValueId>
  ): Map<VariableValueId, BaseVariableValueProperties<VariableValueId>> {
    return with(VARIABLE_VALUES) {
      dslContext
          .select(ID, PROJECT_ID, VARIABLE_ID, LIST_POSITION, CITATION)
          .from(VARIABLE_VALUES)
          .where(ID.`in`(valueIds))
          .fetchMap(ID.asNonNullable()) { record ->
            BaseVariableValueProperties(
                record[ID]!!,
                record[PROJECT_ID]!!,
                record[LIST_POSITION]!!,
                record[VARIABLE_ID]!!,
                record[CITATION],
            )
          }
    }
  }

  /** Returns a project's highest value ID, if any. */
  fun fetchMaxValueId(projectId: ProjectId): VariableValueId? {
    return dslContext
        .select(DSL.max(VARIABLE_VALUES.ID))
        .from(VARIABLE_VALUES)
        .where(VARIABLE_VALUES.PROJECT_ID.eq(projectId))
        .fetchOne(DSL.max(VARIABLE_VALUES.ID))
  }

  fun fetchOneById(valueId: VariableValueId): ExistingValue {
    val projectId = fetchProjectId(valueId)
    val values = listValues(projectId, valueId, valueId)

    return values.firstOrNull() ?: throw VariableValueNotFoundException(valueId)
  }

  fun listValues(
      documentId: DocumentId,
      minValueId: VariableValueId? = null,
      maxValueId: VariableValueId? = null,
  ): List<ExistingValue> {
    val includeDeletedValues = minValueId != null
    val conditions =
        listOfNotNull(
            VARIABLE_VALUES.PROJECT_ID.eq(
                DSL.select(DOCUMENTS.PROJECT_ID).from(DOCUMENTS).where(DOCUMENTS.ID.eq(documentId))
            ),
            minValueId?.let { VARIABLE_VALUES.ID.ge(it) },
            maxValueId?.let { VARIABLE_VALUES.ID.le(it) },
        )

    return fetchByConditions(conditions, includeDeletedValues)
  }

  fun listValues(
      projectId: ProjectId,
      deliverableId: DeliverableId? = null,
      minValueId: VariableValueId? = null,
      maxValueId: VariableValueId? = null,
      variableIds: Collection<VariableId>? = null,
      includeDeletedValues: Boolean = minValueId != null,
      includeReplacedVariables: Boolean = true,
  ): List<ExistingValue> {
    val excludeReplacedCondition =
        if (includeReplacedVariables) {
          null
        } else {
          DSL.notExists(
              DSL.selectOne()
                  .from(VARIABLES)
                  .where(VARIABLES.REPLACES_VARIABLE_ID.eq(VARIABLE_VALUES.VARIABLE_ID))
          )
        }

    val conditions =
        listOfNotNull(
            VARIABLE_VALUES.PROJECT_ID.eq(projectId),
            deliverableId?.let {
              DSL.exists(
                  DSL.selectOne()
                      .from(DELIVERABLE_VARIABLES)
                      .where(DELIVERABLE_VARIABLES.VARIABLE_ID.eq(VARIABLE_VALUES.VARIABLE_ID))
                      .and(DELIVERABLE_VARIABLES.DELIVERABLE_ID.eq(deliverableId))
              )
            },
            minValueId?.let { VARIABLE_VALUES.ID.ge(it) },
            maxValueId?.let { VARIABLE_VALUES.ID.le(it) },
            variableIds?.let { VARIABLE_VALUES.VARIABLE_ID.`in`(it) },
            excludeReplacedCondition,
        )

    return fetchByConditions(conditions, includeDeletedValues)
  }

  /**
   * Returns the values for a list of variable IDs across all projects. Deleted values are not
   * included.
   */
  fun listValues(
      variableIds: Collection<VariableId>,
  ): List<ExistingValue> {
    return fetchByConditions(listOf(VARIABLE_VALUES.VARIABLE_ID.`in`(variableIds)), false)
  }

  /** For each variable, returns which projects have values for it. */
  fun fetchProjectsWithValues(
      variableIds: Collection<VariableId>
  ): Map<VariableId, Set<ProjectId>> {
    return with(VARIABLE_VALUES) {
      dslContext
          .select(VARIABLE_ID, PROJECT_ID)
          .from(VARIABLE_VALUES)
          .where(VARIABLE_VALUES.VARIABLE_ID.`in`(variableIds))
          .fetchGroups(VARIABLE_ID.asNonNullable())
          .mapValues { (_, result) -> result.map { it[PROJECT_ID]!! }.toSet() }
    }
  }

  /**
   * Returns the list position to use when appending a new value to a variable. If the variable is
   * not a list, always returns 0; otherwise returns the current maximum list position plus 1, or 0
   * if there are no existing values yet.
   */
  fun fetchNextListPosition(projectId: ProjectId, variableId: VariableId): Int {
    return dslContext
        .select(
            DSL.case_()
                .`when`(
                    VARIABLES.IS_LIST,
                    DSL.select(DSL.max(VARIABLE_VALUES.LIST_POSITION).plus(1))
                        .from(VARIABLE_VALUES)
                        .where(VARIABLE_VALUES.PROJECT_ID.eq(projectId))
                        .and(VARIABLE_VALUES.VARIABLE_ID.eq(variableId))
                        .and(VARIABLE_VALUES.IS_DELETED.isFalse),
                )
                .else_(0)
        )
        .from(VARIABLES)
        .where(VARIABLES.ID.eq(variableId))
        .fetchOne()
        ?.value1() ?: 0
  }

  // Get the VariableValue for the injected variable within a section variable
  private fun fetchSectionValuesUsingVariable(variableId: VariableId): List<ExistingSectionValue> {
    val variableValues2 = VARIABLE_VALUES.`as`("variable_values_2")
    val conditions =
        listOf(
            VARIABLE_SECTION_VALUES.USED_VARIABLE_ID.eq(variableId),
            // Don't return values that were later superseded by other values
            DSL.notExists(
                DSL.selectOne()
                    .from(variableValues2)
                    .where(variableValues2.VARIABLE_ID.eq(VARIABLE_VALUES.VARIABLE_ID))
                    .and(variableValues2.PROJECT_ID.eq(VARIABLE_VALUES.PROJECT_ID))
                    .and(variableValues2.LIST_POSITION.eq(VARIABLE_VALUES.LIST_POSITION))
                    .and(variableValues2.ID.gt(VARIABLE_VALUES.ID))
            ),
        )

    return fetchByConditions(conditions, false).filterIsInstance<ExistingSectionValue>()
  }

  private fun fetchByConditions(
      conditions: List<Condition>,
      includeDeletedValues: Boolean,
  ): List<ExistingValue> {
    val selectOptionsMultiset =
        DSL.multiset(
                DSL.select(VARIABLE_SELECT_OPTION_VALUES.OPTION_ID)
                    .from(VARIABLE_SELECT_OPTION_VALUES)
                    .where(VARIABLE_SELECT_OPTION_VALUES.VARIABLE_VALUE_ID.eq(VARIABLE_VALUES.ID))
            )
            .convertFrom { result ->
              result.map { record -> record[VARIABLE_SELECT_OPTION_VALUES.OPTION_ID] }.toSet()
            }

    val visibilityCondition =
        if (!currentUser().canReadInternalOnlyVariables()) {
          VARIABLES.INTERNAL_ONLY.eq(false)
        } else {
          null
        }

    val tableRowVariableValues = VARIABLE_VALUES.`as`("table_row_values")

    val valueRecords =
        dslContext
            .select(
                selectOptionsMultiset,
                VARIABLE_IMAGE_VALUES.CAPTION,
                VARIABLE_IMAGE_VALUES.FILE_ID,
                VARIABLE_LINK_VALUES.TITLE,
                VARIABLE_LINK_VALUES.URL,
                VARIABLE_SECTION_VALUES.TEXT_VALUE,
                VARIABLE_SECTION_VALUES.USED_VARIABLE_ID,
                VARIABLE_SECTION_VALUES.USAGE_TYPE_ID,
                VARIABLE_SECTION_VALUES.DISPLAY_STYLE_ID,
                VARIABLE_VALUES.CITATION,
                VARIABLE_VALUES.DATE_VALUE,
                VARIABLE_VALUES.ID,
                VARIABLE_VALUES.IS_DELETED,
                VARIABLE_VALUES.LIST_POSITION,
                VARIABLE_VALUES.NUMBER_VALUE,
                VARIABLE_VALUES.PROJECT_ID,
                VARIABLE_VALUES.TEXT_VALUE,
                VARIABLE_VALUES.VARIABLE_ID,
                VARIABLE_VALUES.VARIABLE_TYPE_ID,
                VARIABLE_VALUE_TABLE_ROWS.TABLE_ROW_VALUE_ID,
                tableRowVariableValues.IS_DELETED,
                tableRowVariableValues.LIST_POSITION,
                tableRowVariableValues.VARIABLE_ID,
            )
            .distinctOn(
                VARIABLE_VALUES.PROJECT_ID,
                VARIABLE_VALUES.VARIABLE_ID,
                VARIABLE_VALUES.LIST_POSITION,
                tableRowVariableValues.VARIABLE_ID,
                tableRowVariableValues.LIST_POSITION,
            )
            .from(VARIABLE_VALUES)
            .leftJoin(VARIABLE_VALUE_TABLE_ROWS)
            .on(VARIABLE_VALUES.ID.eq(VARIABLE_VALUE_TABLE_ROWS.VARIABLE_VALUE_ID))
            .leftJoin(tableRowVariableValues)
            .on(VARIABLE_VALUE_TABLE_ROWS.TABLE_ROW_VALUE_ID.eq(tableRowVariableValues.ID))
            .leftJoin(VARIABLE_IMAGE_VALUES)
            .on(VARIABLE_VALUES.ID.eq(VARIABLE_IMAGE_VALUES.VARIABLE_VALUE_ID))
            .leftJoin(VARIABLE_LINK_VALUES)
            .on(VARIABLE_VALUES.ID.eq(VARIABLE_LINK_VALUES.VARIABLE_VALUE_ID))
            .leftJoin(VARIABLE_SECTION_VALUES)
            .on(VARIABLE_VALUES.ID.eq(VARIABLE_SECTION_VALUES.VARIABLE_VALUE_ID))
            .join(VARIABLES)
            .on(VARIABLE_VALUES.VARIABLE_ID.eq(VARIABLES.ID))
            .where(conditions)
            .and(visibilityCondition)
            .orderBy(
                VARIABLE_VALUES.PROJECT_ID,
                VARIABLE_VALUES.VARIABLE_ID,
                VARIABLE_VALUES.LIST_POSITION,
                tableRowVariableValues.VARIABLE_ID,
                tableRowVariableValues.LIST_POSITION,
                tableRowVariableValues.ID.desc(),
                VARIABLE_VALUES.ID.desc(),
            )
            .fetch()

    return valueRecords.mapNotNull { record ->
      val base =
          BaseVariableValueProperties(
              citation = record[VARIABLE_VALUES.CITATION],
              projectId = record[VARIABLE_VALUES.PROJECT_ID]!!,
              id = record[VARIABLE_VALUES.ID]!!,
              listPosition = record[VARIABLE_VALUES.LIST_POSITION]!!,
              rowValueId = record[VARIABLE_VALUE_TABLE_ROWS.TABLE_ROW_VALUE_ID],
              variableId = record[VARIABLE_VALUES.VARIABLE_ID]!!,
          )

      val variableType = record[VARIABLE_VALUES.VARIABLE_TYPE_ID]!!

      if (record[VARIABLE_VALUES.IS_DELETED] == true) {
        if (includeDeletedValues) {
          ExistingDeletedValue(base, variableType)
        } else {
          // No point returning deleted values for non-incremental queries since they are just
          // snapshots of the current state and the values don't exist in the current state.
          null
        }
      } else if (!includeDeletedValues && record[tableRowVariableValues.IS_DELETED] == true) {
        null
      } else {
        when (variableType) {
          VariableType.Date ->
              record[VARIABLE_VALUES.DATE_VALUE]?.let { ExistingDateValue(base, it) }
          VariableType.Email ->
              record[VARIABLE_VALUES.TEXT_VALUE]?.let { ExistingEmailValue(base, it) }
          VariableType.Image ->
              record[VARIABLE_IMAGE_VALUES.FILE_ID]?.let { fileId ->
                ExistingImageValue(
                    base,
                    ImageValueDetails(record[VARIABLE_IMAGE_VALUES.CAPTION], fileId),
                )
              }
          VariableType.Link ->
              record[VARIABLE_LINK_VALUES.URL]?.let { url ->
                ExistingLinkValue(
                    base,
                    LinkValueDetails(URI(url), record[VARIABLE_LINK_VALUES.TITLE]),
                )
              }
          VariableType.Number ->
              record[VARIABLE_VALUES.NUMBER_VALUE]?.let { ExistingNumberValue(base, it) }
          VariableType.Section ->
              record[VARIABLE_SECTION_VALUES.TEXT_VALUE]?.let {
                ExistingSectionValue(base, SectionValueText(it))
              }
                  ?: record[VARIABLE_SECTION_VALUES.USED_VARIABLE_ID]?.let { variableId ->
                    ExistingSectionValue(
                        base,
                        SectionValueVariable(
                            variableId,
                            record[VARIABLE_SECTION_VALUES.USAGE_TYPE_ID]!!,
                            record[VARIABLE_SECTION_VALUES.DISPLAY_STYLE_ID],
                        ),
                    )
                  }
          VariableType.Select ->
              record[selectOptionsMultiset]?.let { ExistingSelectValue(base, it) }
          VariableType.Table -> ExistingTableValue(base)
          VariableType.Text ->
              record[VARIABLE_VALUES.TEXT_VALUE]?.let { ExistingTextValue(base, it) }
        } ?: throw VariableValueIncompleteException(base.id)
      }
    }
  }

  /** Create operations to populate project document section variables defaults. */
  fun calculateDefaultValues(
      projectId: ProjectId,
      manifestId: VariableManifestId,
      variableId: VariableId? = null,
  ): List<AppendValueOperation> {
    val hasValues =
        dslContext.fetchExists(
            dslContext
                .select()
                .from(VARIABLE_VALUES)
                .join(VARIABLE_MANIFEST_ENTRIES)
                .on(VARIABLE_MANIFEST_ENTRIES.VARIABLE_ID.eq(VARIABLE_VALUES.VARIABLE_ID))
                .where(
                    listOfNotNull(
                        VARIABLE_VALUES.PROJECT_ID.eq(projectId),
                        variableId?.let { VARIABLE_VALUES.VARIABLE_ID.eq(it) },
                    )
                )
                .and(VARIABLE_MANIFEST_ENTRIES.VARIABLE_MANIFEST_ID.eq(manifestId))
        )
    if (hasValues) {
      throw IllegalStateException("Can only populate initial values of variables without values")
    }

    val operations =
        dslContext
            .selectFrom(VARIABLE_SECTION_DEFAULT_VALUES)
            .where(
                listOfNotNull(
                    VARIABLE_SECTION_DEFAULT_VALUES.VARIABLE_MANIFEST_ID.eq(manifestId),
                    variableId?.let { VARIABLE_SECTION_DEFAULT_VALUES.VARIABLE_ID.eq(it) },
                )
            )
            .orderBy(
                VARIABLE_SECTION_DEFAULT_VALUES.VARIABLE_ID,
                VARIABLE_SECTION_DEFAULT_VALUES.LIST_POSITION,
            )
            .map { record ->
              val fragment =
                  record.textValue?.let { SectionValueText(it) }
                      ?: SectionValueVariable(
                          record.usedVariableId!!,
                          record.usageTypeId!!,
                          record.displayStyleId,
                      )
              AppendValueOperation(
                  NewSectionValue(
                      BaseVariableValueProperties(
                          null,
                          projectId,
                          record.listPosition!!,
                          record.variableId!!,
                          null,
                      ),
                      fragment,
                  )
              )
            }

    return operations
  }

  /**
   * Updates the variable references in section default values to point to the newest versions of
   * the variables. This is called after uploading a new all-variables list.
   */
  fun upgradeSectionDefaultValues(replacements: Map<VariableId, VariableId>) {
    dslContext.transaction { _ ->
      val newVariableTypes: Map<VariableId, VariableType> =
          with(VARIABLES) {
            dslContext
                .select(ID, VARIABLE_TYPE_ID)
                .from(VARIABLES)
                .where(ID.`in`(replacements.values))
                .fetchMap(ID.asNonNullable(), VARIABLE_TYPE_ID.asNonNullable())
          }

      replacements.forEach { (oldVariableId, newVariableId) ->
        with(VARIABLE_SECTION_DEFAULT_VALUES) {
          dslContext
              .update(VARIABLE_SECTION_DEFAULT_VALUES)
              .set(USED_VARIABLE_ID, newVariableId)
              .set(USED_VARIABLE_TYPE_ID, newVariableTypes[newVariableId])
              .where(USED_VARIABLE_ID.eq(oldVariableId))
              .execute()
        }
      }
    }
  }

  fun upgradeSectionValueVariables(replacements: Map<VariableId, VariableId>) {
    val sectionOperations =
        replacements.flatMap { (oldVariableId, newVariableId) ->
          fetchSectionValuesUsingVariable(oldVariableId).mapNotNull { sectionValue ->
            val valueVariable = sectionValue.value as? SectionValueVariable
            valueVariable?.let { sectionValueVariable ->
              UpdateValueOperation(
                  ExistingSectionValue(
                      BaseVariableValueProperties(
                          sectionValue.id,
                          sectionValue.projectId,
                          sectionValue.listPosition,
                          sectionValue.variableId,
                          sectionValue.citation,
                      ),
                      SectionValueVariable(
                          newVariableId,
                          sectionValueVariable.usageType,
                          sectionValueVariable.displayStyle,
                      ),
                  )
              )
            }
          }
        }

    sectionOperations
        .groupBy { it.projectId }
        .forEach { updateValues(it.value, triggerWorkflows = false) }
  }

  /**
   * Updates the values in a document by applying a list of operations.
   *
   * @param triggerWorkflows If true, publish events about the updates that trigger additional
   *   actions such as sending notifications and updating submission statuses.
   * @return The values that changed after the operations were applied. In theory it's possible for
   *   this to also include changes from other updates that were running at the same time as this
   *   one.
   */
  fun updateValues(
      operations: List<ValueOperation>,
      triggerWorkflows: Boolean = true,
  ): List<ExistingValue> {
    val projectId = operations.firstOrNull()?.projectId ?: return emptyList()

    if (!triggerWorkflows) {
      // Require internal workflow permission to not use default workflow update
      requirePermissions { updateInternalVariableWorkflowDetails(projectId) }
    }

    val latestRowIds = mutableMapOf<VariableId, VariableValueId>()
    if (operations.any { it.projectId != projectId }) {
      throw IllegalArgumentException("Cannot update values of multiple projects")
    }

    val maxValueIdBefore = fetchMaxValueId(projectId) ?: VariableValueId(0)

    dslContext.transaction { _ ->
      operations.forEach { operation ->
        when (operation) {
          is AppendValueOperation -> appendValue(operation, latestRowIds)
          is DeleteValueOperation -> deleteValue(operation)
          is ReplaceValuesOperation -> replaceValues(operation)
          is UpdateValueOperation -> updateValue(operation)
        }
      }
    }

    val maxValueIdAfter = fetchMaxValueId(projectId) ?: VariableValueId(1)

    val values = listValues(projectId, VariableValueId(maxValueIdBefore.value + 1), maxValueIdAfter)

    publishUpdatedEvent(projectId, values)
    if (triggerWorkflows) {
      notifyForCompletedSections(projectId, values)
    }

    return values
  }

  /** Sets a single value at a specific list position, superseding any previous value. */
  fun writeValue(newValue: NewValue): VariableValueId {
    val variablesRow =
        variablesDao.fetchOneById(newValue.variableId)
            ?: throw VariableNotFoundException(newValue.variableId)

    if (variablesRow.variableTypeId != newValue.type) {
      throw VariableTypeMismatchException(newValue.variableId, newValue.type)
    }

    val result =
        dslContext.transactionResult { _ ->
          insertValue(newValue.projectId, newValue.listPosition, newValue.rowValueId, newValue)
        }

    // notify variable value was updated
    eventPublisher.publishEvent(VariableValueUpdatedEvent(newValue.projectId, newValue.variableId))

    return result
  }

  private fun appendValue(
      operation: AppendValueOperation,
      latestRowIds: MutableMap<VariableId, VariableValueId>,
  ): VariableValueId {
    val projectId = operation.projectId
    val variableId = operation.value.variableId
    val variablesRow =
        variablesDao.fetchOneById(variableId) ?: throw VariableNotFoundException(variableId)

    // If this variable is a table column, we need to do one of:
    // - If the client supplied a row ID, validate that it is a row of the correct table
    // - Otherwise, if we've just inserted a row into the column's table, use that row's ID (to
    //   support inserting a row with values in one API request)
    // - Otherwise, it's an error: we don't know which row to assign the value to.

    val inputRowValueId = operation.value.rowValueId
    val containingTableId = fetchContainingTableVariableId(variableId)

    val actualRowValueId: VariableValueId? =
        when {
          inputRowValueId != null -> {
            if (containingTableId == null) {
              throw VariableNotInTableException(variableId)
            }
            val rowTableId = fetchVariableId(inputRowValueId)
            if (containingTableId != rowTableId) {
              throw RowInWrongTableException(variableId, inputRowValueId)
            } else {
              inputRowValueId
            }
          }
          containingTableId == null -> null
          containingTableId in latestRowIds -> latestRowIds[containingTableId]
          else -> throw VariableInTableException(variableId)
        }

    val listPosition =
        if (variablesRow.isList == true) {
          fetchNextListPosition(projectId, variableId, actualRowValueId)
        } else {
          0
        }

    val valueId = insertValue(projectId, listPosition, actualRowValueId, operation.value)

    if (variablesRow.variableTypeId == VariableType.Table) {
      latestRowIds[variableId] = valueId
    }

    logVariableOperation("Appended value", projectId, variablesRow, newValueId = valueId)

    return valueId
  }

  private fun deleteValue(
      operation: DeleteValueOperation,
      targetRowValueId: VariableValueId? = null,
  ) {
    val valueId = operation.valueId
    val variableId = fetchVariableId(valueId)
    val valuesRow =
        variableValuesDao.fetchOneById(valueId) ?: throw VariableValueNotFoundException(valueId)
    val variablesRow =
        variablesDao.fetchOneById(variableId) ?: throw VariableNotFoundException(variableId)
    val containingRowId = fetchContainingRowId(valueId)
    val listPosition = valuesRow.listPosition!!
    val projectId = operation.projectId

    val isOutdated =
        dslContext
            .selectOne()
            .from(VARIABLE_VALUES)
            .leftJoin(VARIABLE_VALUE_TABLE_ROWS)
            .on(VARIABLE_VALUES.ID.eq(VARIABLE_VALUE_TABLE_ROWS.VARIABLE_VALUE_ID))
            .where(VARIABLE_VALUES.PROJECT_ID.eq(projectId))
            .and(VARIABLE_VALUES.VARIABLE_ID.eq(variableId))
            .and(VARIABLE_VALUES.LIST_POSITION.eq(listPosition))
            .and(
                if (containingRowId != null) {
                  VARIABLE_VALUE_TABLE_ROWS.TABLE_ROW_VALUE_ID.eq(containingRowId)
                } else {
                  VARIABLE_VALUE_TABLE_ROWS.TABLE_ROW_VALUE_ID.isNull
                }
            )
            .and(VARIABLE_VALUES.ID.gt(valueId))
            .fetch()
            .isNotEmpty

    if (isOutdated) {
      logVariableOperation("Ignoring deletion of outdated value", projectId, variablesRow, valueId)
      return
    }

    val maxListPosition =
        fetchMaxListPosition(projectId, variableId, containingRowId)
            ?: throw IllegalStateException(
                "Project $projectId variable $variableId has values but no max list position"
            )

    val deletedValue =
        DeletedValue(
            BaseVariableValueProperties(
                citation = null,
                id = null,
                listPosition = listPosition,
                projectId = projectId,
                rowValueId = containingRowId,
                variableId = variableId,
            ),
            variablesRow.variableTypeId!!,
        )

    // If there were later values with higher list positions, renumber them to avoid holes in the
    // sequence of list positions.
    val valuesToRenumber =
        fetchByConditions(
            listOfNotNull(
                VARIABLE_VALUES.PROJECT_ID.eq(projectId),
                VARIABLE_VALUES.VARIABLE_ID.eq(variableId),
                containingRowId?.let { VARIABLE_VALUE_TABLE_ROWS.TABLE_ROW_VALUE_ID.eq(it) },
                VARIABLE_VALUES.LIST_POSITION.gt(listPosition),
            ),
            false,
        )

    valuesToRenumber.forEach { valueToRenumber ->
      val newValueId =
          insertValue(projectId, valueToRenumber.listPosition - 1, containingRowId, valueToRenumber)

      // If we're deleting a table row, need to associate the existing cell values with the newly-
      // renumbered rows.
      if (valueToRenumber is ExistingTableValue) {
        val newValueTableRows =
            variableValueTableRowsDao.fetchByTableRowValueId(valueToRenumber.id).map {
                existingValueTableRow ->
              VariableValueTableRowsRow(
                  tableRowValueId = newValueId,
                  variableValueId = existingValueTableRow.variableValueId,
              )
            }

        variableValueTableRowsDao.insert(newValueTableRows)
      }
    }

    val deletedId =
        insertValue(projectId, maxListPosition, targetRowValueId ?: containingRowId, deletedValue)

    // If this was a table row, recursively delete all its column values.
    if (variablesRow.variableTypeId == VariableType.Table) {
      val deletedRows = VARIABLE_VALUE_TABLE_ROWS.`as`("deleted_rows")
      val deletedValues = VARIABLE_VALUES.`as`("deleted_values")

      // Delete in reverse list position order so we don't waste time renumbering.
      val cellValueIds =
          dslContext
              .select(VARIABLE_VALUE_TABLE_ROWS.VARIABLE_VALUE_ID)
              .from(VARIABLE_VALUE_TABLE_ROWS)
              .join(VARIABLE_VALUES)
              .on(VARIABLE_VALUE_TABLE_ROWS.VARIABLE_VALUE_ID.eq(VARIABLE_VALUES.ID))
              .where(VARIABLE_VALUE_TABLE_ROWS.TABLE_ROW_VALUE_ID.eq(valueId))
              .and(VARIABLE_VALUES.IS_DELETED.isFalse)
              .andNotExists(
                  DSL.selectOne()
                      .from(deletedValues)
                      .join(deletedRows)
                      .on(deletedValues.ID.eq(deletedRows.VARIABLE_VALUE_ID))
                      .where(deletedValues.VARIABLE_ID.eq(VARIABLE_VALUES.VARIABLE_ID))
                      .and(deletedRows.TABLE_ROW_VALUE_ID.eq(valueId))
                      .and(deletedValues.LIST_POSITION.eq(VARIABLE_VALUES.LIST_POSITION))
                      .and(deletedValues.IS_DELETED.isTrue)
                      .and(deletedValues.ID.gt(VARIABLE_VALUES.ID))
              )
              .orderBy(VARIABLE_VALUES.LIST_POSITION.desc())
              .fetch(VARIABLE_VALUE_TABLE_ROWS.VARIABLE_VALUE_ID.asNonNullable())

      cellValueIds.forEach { cellValueId ->
        deleteValue(DeleteValueOperation(projectId, cellValueId), deletedId)
      }
    }

    logVariableOperation("Deleted value", projectId, variablesRow, valueId, deletedId)
  }

  private fun listValues(
      projectId: ProjectId,
      minValueId: VariableValueId? = null,
      maxValueId: VariableValueId? = null,
  ): List<ExistingValue> = listValues(projectId, null, minValueId, maxValueId)

  private fun replaceValues(operation: ReplaceValuesOperation) {
    val projectId = operation.projectId
    val variableId = operation.variableId
    val variablesRow =
        variablesDao.fetchOneById(variableId) ?: throw VariableNotFoundException(variableId)

    if (operation.values.size > 1 && variablesRow.isList != true) {
      throw VariableNotListException(variableId)
    }

    if (variablesRow.isList == true) {
      // If the replacement list has fewer items than the existing value, delete the items whose
      // list positions no longer exist.

      val maxListPosition = fetchMaxListPosition(projectId, variableId, operation.rowValueId)
      if (maxListPosition != null) {
        (operation.values.size..maxListPosition).forEach { listPosition ->
          val deletedValue =
              DeletedValue(
                  BaseVariableValueProperties(
                      citation = null,
                      id = null,
                      listPosition = listPosition,
                      projectId = projectId,
                      rowValueId = operation.rowValueId,
                      variableId = operation.variableId,
                  ),
                  variablesRow.variableTypeId!!,
              )

          insertValue(projectId, listPosition, operation.rowValueId, deletedValue)
        }
      }
    }

    operation.values.forEachIndexed { listPosition, value ->
      insertValue(projectId, listPosition, operation.rowValueId, value)
    }

    logVariableOperation("Replaced values", projectId, variablesRow)
  }

  private fun updateValue(operation: UpdateValueOperation) {
    val projectId = operation.projectId
    val variableId = operation.value.variableId
    val variablesRow =
        variablesDao.fetchOneById(variableId) ?: throw VariableNotFoundException(variableId)
    val valueId = operation.value.id
    val rowValueId = fetchContainingRowId(valueId)

    // Updating images is a special case, because we need to carry the file ID over from the
    // existing value.
    val effectiveValue =
        if (operation.value is ImageValue<*>) {
          val existingImageValue =
              variableImageValuesDao.fetchOneByVariableValueId(valueId)
                  ?: throw VariableValueIncompleteException(valueId)
          ExistingImageValue(
              BaseVariableValueProperties(
                  citation = operation.value.citation,
                  id = valueId,
                  listPosition = operation.value.listPosition,
                  projectId = projectId,
                  rowValueId = operation.value.rowValueId,
                  variableId = operation.value.variableId,
              ),
              ImageValueDetails(operation.value.value.caption, existingImageValue.fileId!!),
          )
        } else {
          operation.value
        }

    val newValueId =
        insertValue(projectId, operation.value.listPosition, rowValueId, effectiveValue)

    logVariableOperation("Updated value", projectId, variablesRow, valueId, newValueId)
  }

  private fun insertValue(
      projectId: ProjectId,
      listPosition: Int,
      rowValueId: VariableValueId?,
      value: VariableValue<*, *>,
  ): VariableValueId {
    variablesDao.fetchOneById(value.variableId) ?: throw VariableNotFoundException(value.variableId)

    val textValue =
        when (value) {
          is EmailValue -> value.value
          is TextValue -> value.value
          else -> null
        }

    val valuesRow =
        VariableValuesRow(
            citation = value.citation,
            createdBy = currentUser().userId,
            createdTime = clock.instant(),
            dateValue = if (value is DateValue) value.value else null,
            isDeleted = value is DeletedValue,
            listPosition = listPosition,
            numberValue = if (value is NumberValue) value.value else null,
            projectId = projectId,
            textValue = textValue,
            variableId = value.variableId,
            variableTypeId = value.type,
        )

    variableValuesDao.insert(valuesRow)

    val valueId = valuesRow.id!!

    if (rowValueId != null) {
      variableValueTableRowsDao.insert(
          VariableValueTableRowsRow(tableRowValueId = rowValueId, variableValueId = valueId)
      )
    }

    when (value) {
      is DeletedValue,
      is NumberValue,
      is TextValue,
      is DateValue,
      is EmailValue,
      is TableValue -> {} // Some values don't require any rows in child tables.
      is ImageValue -> insertImageValue(valueId, value)
      is LinkValue -> insertLinkValue(valueId, value)
      is SectionValue -> insertSectionValue(valueId, value)
      is SelectValue -> insertSelectValue(valueId, value)
    }

    return valueId
  }

  private fun insertImageValue(valueId: VariableValueId, value: ImageValue<*>) {
    variableImageValuesDao.insert(
        VariableImageValuesRow(
            caption = value.value.caption,
            fileId = value.value.fileId,
            variableId = value.variableId,
            variableTypeId = VariableType.Image,
            variableValueId = valueId,
        )
    )
  }

  private fun insertLinkValue(valueId: VariableValueId, value: LinkValue<*>) {
    variableLinkValuesDao.insert(
        VariableLinkValuesRow(
            title = value.value.title,
            url = value.value.url.toString(),
            variableId = value.variableId,
            variableTypeId = VariableType.Link,
            variableValueId = valueId,
        )
    )
  }

  private fun insertSectionValue(valueId: VariableValueId, value: SectionValue<*>) {
    val row =
        when (value.value) {
          is SectionValueText ->
              VariableSectionValuesRow(
                  textValue = value.value.textValue,
                  variableId = value.variableId,
                  variableTypeId = VariableType.Section,
                  variableValueId = valueId,
              )
          is SectionValueVariable -> {
            val usedVariablesRow =
                variablesDao.fetchOneById(value.value.usedVariableId)
                    ?: throw VariableNotFoundException(value.value.usedVariableId)
            VariableSectionValuesRow(
                displayStyleId = value.value.displayStyle,
                textValue = null,
                usageTypeId = value.value.usageType,
                usedVariableId = value.value.usedVariableId,
                usedVariableTypeId = usedVariablesRow.variableTypeId,
                variableId = value.variableId,
                variableTypeId = VariableType.Section,
                variableValueId = valueId,
            )
          }
        }

    variableSectionValuesDao.insert(row)
  }

  private fun insertSelectValue(valueId: VariableValueId, value: SelectValue<*>) {
    val rows =
        value.value.map { optionId ->
          VariableSelectOptionValuesRow(
              optionId = optionId,
              variableId = value.variableId,
              variableTypeId = VariableType.Select,
              variableValueId = valueId,
          )
        }

    variableSelectOptionValuesDao.insert(rows)
  }

  private fun fetchVariableId(valueId: VariableValueId): VariableId {
    return with(VARIABLE_VALUES) {
      dslContext
          .select(VARIABLE_ID)
          .from(VARIABLE_VALUES)
          .where(ID.eq(valueId))
          .fetchOne(VARIABLE_ID) ?: throw VariableValueNotFoundException(valueId)
    }
  }

  /**
   * Returns the ID of the most recent table row that contains a value. A value can be contained in
   * more than one table row if rows have been deleted from the table in the past; in that case, we
   * move the remaining values to newly-created rows with the correct list positions.
   */
  private fun fetchContainingRowId(valueId: VariableValueId): VariableValueId? {
    return with(VARIABLE_VALUE_TABLE_ROWS) {
      dslContext
          .select(DSL.max(TABLE_ROW_VALUE_ID))
          .from(VARIABLE_VALUE_TABLE_ROWS)
          .where(VARIABLE_VALUE_ID.eq(valueId))
          .fetchOne()
          ?.value1()
    }
  }

  private fun fetchContainingTableVariableId(columnVariableId: VariableId): VariableId? {
    return with(VARIABLE_TABLE_COLUMNS) {
      dslContext
          .select(TABLE_VARIABLE_ID)
          .from(VARIABLE_TABLE_COLUMNS)
          .where(VARIABLE_ID.eq(columnVariableId))
          .fetchOne(TABLE_VARIABLE_ID)
    }
  }

  private fun fetchMaxListPosition(
      projectId: ProjectId,
      variableId: VariableId,
      rowValueId: VariableValueId?,
  ): Int? {
    val deletedValues = VARIABLE_VALUES.`as`("deleted_values")
    val deletedRows = VARIABLE_VALUE_TABLE_ROWS.`as`("deleted_rows")

    return if (rowValueId == null) {
      dslContext
          .select(DSL.max(VARIABLE_VALUES.LIST_POSITION))
          .from(VARIABLE_VALUES)
          .where(VARIABLE_VALUES.VARIABLE_ID.eq(variableId))
          .and(VARIABLE_VALUES.PROJECT_ID.eq(projectId))
          .and(VARIABLE_VALUES.IS_DELETED.isFalse)
          .andNotExists(
              DSL.selectOne()
                  .from(deletedValues)
                  .where(deletedValues.VARIABLE_ID.eq(variableId))
                  .and(deletedValues.PROJECT_ID.eq(projectId))
                  .and(deletedValues.LIST_POSITION.eq(VARIABLE_VALUES.LIST_POSITION))
                  .and(deletedValues.IS_DELETED.isTrue)
                  .and(deletedValues.ID.gt(VARIABLE_VALUES.ID))
          )
          .fetchOne()
          ?.value1()
    } else {
      dslContext
          .select(DSL.max(VARIABLE_VALUES.LIST_POSITION))
          .from(VARIABLE_VALUES)
          .join(VARIABLE_VALUE_TABLE_ROWS)
          .on(VARIABLE_VALUES.ID.eq(VARIABLE_VALUE_TABLE_ROWS.VARIABLE_VALUE_ID))
          .where(VARIABLE_VALUES.VARIABLE_ID.eq(variableId))
          .and(VARIABLE_VALUES.PROJECT_ID.eq(projectId))
          .and(VARIABLE_VALUE_TABLE_ROWS.TABLE_ROW_VALUE_ID.eq(rowValueId))
          .and(VARIABLE_VALUES.IS_DELETED.isFalse)
          .andNotExists(
              DSL.selectOne()
                  .from(deletedValues)
                  .join(deletedRows)
                  .on(deletedValues.ID.eq(deletedRows.VARIABLE_VALUE_ID))
                  .where(deletedValues.VARIABLE_ID.eq(variableId))
                  .and(deletedValues.PROJECT_ID.eq(projectId))
                  .and(deletedRows.TABLE_ROW_VALUE_ID.eq(rowValueId))
                  .and(deletedValues.LIST_POSITION.eq(VARIABLE_VALUES.LIST_POSITION))
                  .and(deletedValues.IS_DELETED.isTrue)
                  .and(deletedValues.ID.gt(VARIABLE_VALUES.ID))
          )
          .fetchOne()
          ?.value1()
    }
  }

  private fun fetchNextListPosition(
      projectId: ProjectId,
      variableId: VariableId,
      rowValueId: VariableValueId?,
  ): Int {
    return fetchMaxListPosition(projectId, variableId, rowValueId)?.let { it + 1 } ?: 0
  }

  private fun fetchProjectId(valueId: VariableValueId): ProjectId {
    return dslContext.fetchValue(VARIABLE_VALUES.PROJECT_ID, VARIABLE_VALUES.ID.eq(valueId))
        ?: throw VariableValueNotFoundException(valueId)
  }

  private fun notifyForCompletedSections(
      projectId: ProjectId,
      values: List<ExistingValue>,
  ) {
    val updatedVariableIds = values.map { it.variableId }.toSet()

    val newerValuesTable = VARIABLE_VALUES.`as`("newer_values")

    val childSectionEvents =
        dslContext
            .selectDistinct(VARIABLE_VALUES.VARIABLE_ID, DOCUMENTS.ID)
            .from(VARIABLE_SECTION_VALUES)
            .join(VARIABLE_VALUES)
            .on(VARIABLE_SECTION_VALUES.VARIABLE_VALUE_ID.eq(VARIABLE_VALUES.ID))
            .join(VARIABLE_MANIFEST_ENTRIES)
            .on(VARIABLE_VALUES.VARIABLE_ID.eq(VARIABLE_MANIFEST_ENTRIES.VARIABLE_ID))
            .join(DOCUMENTS)
            .on(VARIABLE_MANIFEST_ENTRIES.VARIABLE_MANIFEST_ID.eq(DOCUMENTS.VARIABLE_MANIFEST_ID))
            .and(VARIABLE_VALUES.PROJECT_ID.eq(DOCUMENTS.PROJECT_ID))
            .where(VARIABLE_SECTION_VALUES.USED_VARIABLE_ID.`in`(updatedVariableIds))
            .and(VARIABLE_VALUES.PROJECT_ID.eq(projectId))
            .andNotExists(
                DSL.selectOne()
                    .from(newerValuesTable)
                    .where(VARIABLE_VALUES.VARIABLE_ID.eq(newerValuesTable.VARIABLE_ID))
                    .and(VARIABLE_VALUES.PROJECT_ID.eq(projectId))
                    .and(VARIABLE_VALUES.LIST_POSITION.eq(newerValuesTable.LIST_POSITION))
                    .and(VARIABLE_VALUES.ID.lt(newerValuesTable.ID))
            )
            .fetch { record ->
              CompletedSectionVariableUpdatedEvent(
                  documentId = record[DOCUMENTS.ID]!!,
                  projectId = projectId,
                  referencingSectionVariableId = record[VARIABLE_VALUES.VARIABLE_ID]!!,
                  sectionVariableId = record[VARIABLE_VALUES.VARIABLE_ID]!!,
              )
            }

    fun addParentEvents(
        event: CompletedSectionVariableUpdatedEvent
    ): List<CompletedSectionVariableUpdatedEvent> {
      val parentSectionId =
          dslContext
              .select(VARIABLE_SECTIONS.PARENT_VARIABLE_ID)
              .from(VARIABLE_SECTIONS)
              .where(VARIABLE_SECTIONS.VARIABLE_ID.eq(event.sectionVariableId))
              .fetchOne(VARIABLE_SECTIONS.PARENT_VARIABLE_ID)

      return if (parentSectionId != null) {
        val parentEvent = event.copy(sectionVariableId = parentSectionId)
        listOf(event) + addParentEvents(parentEvent)
      } else {
        listOf(event)
      }
    }

    // This is the list of events for all child and parent sections, regardless of completion status
    val allSectionEvents = childSectionEvents.flatMap { addParentEvents(it) }.toSet()

    val sectionStatuses: Map<Pair<ProjectId, VariableId>, VariableWorkflowStatus> =
        with(VARIABLE_WORKFLOW_HISTORY) {
          dslContext
              .select(PROJECT_ID, VARIABLE_ID, VARIABLE_WORKFLOW_STATUS_ID)
              .distinctOn(PROJECT_ID, VARIABLE_ID)
              .from(VARIABLE_WORKFLOW_HISTORY)
              .where(
                  DSL.row(PROJECT_ID, VARIABLE_ID)
                      .`in`(allSectionEvents.map { DSL.row(projectId, it.sectionVariableId) })
              )
              .orderBy(PROJECT_ID, VARIABLE_ID, ID.desc())
              .fetch { (projectId, variableId, status) ->
                (projectId!! to variableId!!) to status!!
              }
              .toMap()
        }

    allSectionEvents.forEach { event ->
      val sectionStatus = sectionStatuses[event.projectId to event.sectionVariableId]
      if (sectionStatus == VariableWorkflowStatus.Complete) {
        eventPublisher.publishEvent(event)
      }
    }
  }

  private fun publishUpdatedEvent(
      projectId: ProjectId,
      values: List<ExistingValue>,
  ) {
    val valuesByVariables = values.groupBy { it.variableId }

    valuesByVariables.keys.forEach { variableId ->
      eventPublisher.publishEvent(VariableValueUpdatedEvent(projectId, variableId))
    }
  }

  private fun logVariableOperation(
      message: String,
      projectId: ProjectId,
      variablesRow: VariablesRow,
      oldValueId: VariableValueId? = null,
      newValueId: VariableValueId? = null,
  ) {
    val attributes =
        listOfNotNull(
                "projectId" to projectId,
                "variableId" to variablesRow.id,
                "variableName" to variablesRow.name,
                "variableType" to variablesRow.variableTypeId,
                oldValueId?.let { "oldValueId" to it },
                newValueId?.let { "newValueId" to it },
            )
            .toTypedArray()

    log.withMDC(*attributes) { log.debug(message) }
  }
}
