package com.terraformation.pdd.variable.db

import com.terraformation.pdd.api.currentUser
import com.terraformation.pdd.db.asNonNullable
import com.terraformation.pdd.document.db.DocumentNotFoundException
import com.terraformation.pdd.jooq.DocumentId
import com.terraformation.pdd.jooq.VariableId
import com.terraformation.pdd.jooq.VariableManifestId
import com.terraformation.pdd.jooq.VariableType
import com.terraformation.pdd.jooq.VariableValueId
import com.terraformation.pdd.jooq.tables.daos.DocumentsDao
import com.terraformation.pdd.jooq.tables.daos.VariableImageValuesDao
import com.terraformation.pdd.jooq.tables.daos.VariableLinkValuesDao
import com.terraformation.pdd.jooq.tables.daos.VariableSectionValuesDao
import com.terraformation.pdd.jooq.tables.daos.VariableSelectOptionValuesDao
import com.terraformation.pdd.jooq.tables.daos.VariableValueTableRowsDao
import com.terraformation.pdd.jooq.tables.daos.VariableValuesDao
import com.terraformation.pdd.jooq.tables.daos.VariablesDao
import com.terraformation.pdd.jooq.tables.pojos.VariableImageValuesRow
import com.terraformation.pdd.jooq.tables.pojos.VariableLinkValuesRow
import com.terraformation.pdd.jooq.tables.pojos.VariableSectionValuesRow
import com.terraformation.pdd.jooq.tables.pojos.VariableSelectOptionValuesRow
import com.terraformation.pdd.jooq.tables.pojos.VariableValueTableRowsRow
import com.terraformation.pdd.jooq.tables.pojos.VariableValuesRow
import com.terraformation.pdd.jooq.tables.references.VARIABLES
import com.terraformation.pdd.jooq.tables.references.VARIABLE_IMAGE_VALUES
import com.terraformation.pdd.jooq.tables.references.VARIABLE_LINK_VALUES
import com.terraformation.pdd.jooq.tables.references.VARIABLE_MANIFEST_ENTRIES
import com.terraformation.pdd.jooq.tables.references.VARIABLE_SECTION_VALUES
import com.terraformation.pdd.jooq.tables.references.VARIABLE_SELECT_OPTION_VALUES
import com.terraformation.pdd.jooq.tables.references.VARIABLE_TABLE_COLUMNS
import com.terraformation.pdd.jooq.tables.references.VARIABLE_VALUES
import com.terraformation.pdd.jooq.tables.references.VARIABLE_VALUE_TABLE_ROWS
import com.terraformation.pdd.log.perClassLogger
import com.terraformation.pdd.variable.model.AppendValueOperation
import com.terraformation.pdd.variable.model.BaseVariableValueProperties
import com.terraformation.pdd.variable.model.DateValue
import com.terraformation.pdd.variable.model.DeleteValueOperation
import com.terraformation.pdd.variable.model.DeletedValue
import com.terraformation.pdd.variable.model.ExistingDateValue
import com.terraformation.pdd.variable.model.ExistingDeletedValue
import com.terraformation.pdd.variable.model.ExistingImageValue
import com.terraformation.pdd.variable.model.ExistingLinkValue
import com.terraformation.pdd.variable.model.ExistingNumberValue
import com.terraformation.pdd.variable.model.ExistingSectionValue
import com.terraformation.pdd.variable.model.ExistingSelectValue
import com.terraformation.pdd.variable.model.ExistingTableValue
import com.terraformation.pdd.variable.model.ExistingTextValue
import com.terraformation.pdd.variable.model.ExistingValue
import com.terraformation.pdd.variable.model.ImageValue
import com.terraformation.pdd.variable.model.ImageValueDetails
import com.terraformation.pdd.variable.model.LinkValue
import com.terraformation.pdd.variable.model.LinkValueDetails
import com.terraformation.pdd.variable.model.NewValue
import com.terraformation.pdd.variable.model.NumberValue
import com.terraformation.pdd.variable.model.ReplaceValuesOperation
import com.terraformation.pdd.variable.model.SectionValue
import com.terraformation.pdd.variable.model.SectionValueText
import com.terraformation.pdd.variable.model.SectionValueVariable
import com.terraformation.pdd.variable.model.SelectValue
import com.terraformation.pdd.variable.model.TableValue
import com.terraformation.pdd.variable.model.TextValue
import com.terraformation.pdd.variable.model.UpdateValueOperation
import com.terraformation.pdd.variable.model.ValueOperation
import com.terraformation.pdd.variable.model.VariableValue
import jakarta.inject.Named
import java.net.URI
import java.time.InstantSource
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL

@Named
class VariableValueStore(
    private val clock: InstantSource,
    private val documentsDao: DocumentsDao,
    private val dslContext: DSLContext,
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
          .select(ID, DOCUMENT_ID, VARIABLE_ID, LIST_POSITION, CITATION)
          .from(VARIABLE_VALUES)
          .where(ID.`in`(valueIds))
          .fetchMap(ID.asNonNullable()) { record ->
            BaseVariableValueProperties(
                record[ID]!!,
                record[DOCUMENT_ID]!!,
                record[LIST_POSITION]!!,
                record[VARIABLE_ID]!!,
                record[CITATION],
            )
          }
    }
  }

  /** Returns a document's highest value ID, if any. */
  fun fetchMaxValueId(documentId: DocumentId): VariableValueId? {
    return with(VARIABLE_VALUES) {
      dslContext
          .select(DSL.max(ID))
          .from(VARIABLE_VALUES)
          .where(DOCUMENT_ID.eq(documentId))
          .fetchOne(DSL.max(ID))
    }
  }

  fun fetchOneById(valueId: VariableValueId): ExistingValue {
    val documentId = fetchDocumentId(valueId)
    val values = listValues(documentId, valueId, valueId)

    return values.firstOrNull() ?: throw VariableValueNotFoundException(valueId)
  }

  fun listValues(
      documentId: DocumentId,
      minValueId: VariableValueId? = null,
      maxValueId: VariableValueId? = null
  ): List<ExistingValue> {
    val includeDeletedValues = minValueId != null
    val conditions =
        listOfNotNull(
            VARIABLE_VALUES.DOCUMENT_ID.eq(documentId),
            minValueId?.let { VARIABLE_VALUES.ID.ge(it) },
            maxValueId?.let { VARIABLE_VALUES.ID.le(it) },
        )

    return fetchByConditions(conditions, includeDeletedValues)
  }

  /**
   * Returns the list position to use when appending a new value to a variable. If the variable is
   * not a list, always returns 0; otherwise returns the current maximum list position plus 1, or 0
   * if there are no existing values yet.
   */
  fun fetchNextListPosition(documentId: DocumentId, variableId: VariableId): Int {
    return dslContext
        .select(
            DSL.case_()
                .`when`(
                    VARIABLES.IS_LIST,
                    DSL.select(DSL.max(VARIABLE_VALUES.LIST_POSITION).plus(1))
                        .from(VARIABLE_VALUES)
                        .where(VARIABLE_VALUES.DOCUMENT_ID.eq(documentId))
                        .and(VARIABLE_VALUES.VARIABLE_ID.eq(variableId))
                        .and(VARIABLE_VALUES.IS_DELETED.isFalse))
                .else_(0))
        .from(VARIABLES)
        .where(VARIABLES.ID.eq(variableId))
        .fetchOne()
        ?.value1() ?: 0
  }

  private fun fetchByConditions(
      conditions: List<Condition>,
      includeDeletedValues: Boolean
  ): List<ExistingValue> {
    val selectOptionsMultiset =
        DSL.multiset(
                DSL.select(VARIABLE_SELECT_OPTION_VALUES.OPTION_ID)
                    .from(VARIABLE_SELECT_OPTION_VALUES)
                    .where(VARIABLE_SELECT_OPTION_VALUES.VARIABLE_VALUE_ID.eq(VARIABLE_VALUES.ID)))
            .convertFrom { result ->
              result.map { record -> record[VARIABLE_SELECT_OPTION_VALUES.OPTION_ID] }.toSet()
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
                VARIABLE_VALUES.DOCUMENT_ID,
                VARIABLE_VALUES.TEXT_VALUE,
                VARIABLE_VALUES.VARIABLE_ID,
                VARIABLE_VALUES.VARIABLE_TYPE_ID,
                VARIABLE_VALUE_TABLE_ROWS.TABLE_ROW_VALUE_ID,
                tableRowVariableValues.IS_DELETED,
                tableRowVariableValues.LIST_POSITION,
                tableRowVariableValues.VARIABLE_ID,
            )
            .distinctOn(
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
            .where(conditions)
            .orderBy(
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
              documentId = record[VARIABLE_VALUES.DOCUMENT_ID]!!,
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

  /**
   * Updates the values in a document by applying a list of operations.
   *
   * @return The values that changed after the operations were applied. In theory it's possible for
   *   this to also include changes from other updates that were running at the same time as this
   *   one.
   */
  fun updateValues(operations: List<ValueOperation>): List<ExistingValue> {
    val documentId = operations.firstOrNull()?.documentId ?: return emptyList()

    val latestRowIds = mutableMapOf<VariableId, VariableValueId>()
    if (operations.any { it.documentId != documentId }) {
      throw IllegalArgumentException("Cannot update values of multiple documents")
    }

    val maxValueIdBefore = fetchMaxValueId(documentId) ?: VariableValueId(0)

    dslContext.transaction { _ ->
      val documentsRow =
          documentsDao.fetchOneById(documentId) ?: throw DocumentNotFoundException(documentId)
      val manifestId = documentsRow.variableManifestId!!

      operations.forEach { operation ->
        when (operation) {
          is AppendValueOperation -> appendValue(operation, manifestId, latestRowIds)
          is DeleteValueOperation -> deleteValue(operation)
          is ReplaceValuesOperation -> replaceValues(operation, manifestId)
          is UpdateValueOperation -> updateValue(operation, manifestId)
        }
      }
    }

    val maxValueIdAfter = fetchMaxValueId(documentId) ?: VariableValueId(1)

    return listValues(documentId, VariableValueId(maxValueIdBefore.value + 1), maxValueIdAfter)
  }

  /** Sets a single value at a specific list position, superseding any previous value. */
  fun writeValue(newValue: NewValue): VariableValueId {
    val variablesRow =
        variablesDao.fetchOneById(newValue.variableId)
            ?: throw VariableNotFoundException(newValue.variableId)

    if (variablesRow.variableTypeId != newValue.type) {
      throw VariableTypeMismatchException(newValue.variableId, newValue.type)
    }

    return dslContext.transactionResult { _ ->
      insertValue(newValue.documentId, newValue.listPosition, newValue.rowValueId, newValue)
    }
  }

  private fun appendValue(
      operation: AppendValueOperation,
      manifestId: VariableManifestId,
      latestRowIds: MutableMap<VariableId, VariableValueId>
  ): VariableValueId {
    val documentId = operation.documentId
    val variableId = operation.value.variableId
    val variablesRow =
        variablesDao.fetchOneById(variableId) ?: throw VariableNotFoundException(variableId)

    // If this variable is a table column, we need to do one of:
    // - If the client supplied a row ID, validate that it is a row of the correct table
    // - Otherwise, if we've just inserted a row into the column's table, use that row's ID (to
    //   support inserting a row with values in one API request)
    // - Otherwise, it's an error: we don't know which row to assign the value to.

    if (!isVariableInManifest(variableId, manifestId)) {
      throw VariableNotInManifestException(variableId, manifestId)
    }

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
          fetchNextListPosition(documentId, variableId, actualRowValueId)
        } else {
          0
        }

    val valueId = insertValue(documentId, listPosition, actualRowValueId, operation.value)

    if (variablesRow.variableTypeId == VariableType.Table) {
      latestRowIds[variableId] = valueId
    }

    log.debug(
        "Inserted value $valueId for variable $variableId type ${variablesRow.variableTypeId}")

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
    val maxListPosition =
        fetchMaxListPosition(operation.documentId, variableId, containingRowId)
            ?: throw IllegalStateException(
                "Variable $variableId has values but no max list position")

    val deletedValue =
        DeletedValue(
            BaseVariableValueProperties(
                citation = null,
                documentId = operation.documentId,
                id = null,
                listPosition = listPosition,
                rowValueId = containingRowId,
                variableId = variableId,
            ),
            variablesRow.variableTypeId!!)

    // If there were later values with higher list positions, renumber them to avoid holes in the
    // sequence of list positions.
    val valuesToRenumber =
        fetchByConditions(
            listOfNotNull(
                VARIABLE_VALUES.DOCUMENT_ID.eq(operation.documentId),
                VARIABLE_VALUES.VARIABLE_ID.eq(variableId),
                containingRowId?.let { VARIABLE_VALUE_TABLE_ROWS.TABLE_ROW_VALUE_ID.eq(it) },
                VARIABLE_VALUES.LIST_POSITION.gt(listPosition),
            ),
            false)

    valuesToRenumber.forEach { valueToRenumber ->
      val newValueId =
          insertValue(
              operation.documentId,
              valueToRenumber.listPosition - 1,
              containingRowId,
              valueToRenumber)

      // If we're deleting a table row, need to associate the existing cell values with the newly-
      // renumbered rows.
      if (valueToRenumber is ExistingTableValue) {
        val newValueTableRows =
            variableValueTableRowsDao.fetchByTableRowValueId(valueToRenumber.id).map {
                existingValueTableRow ->
              VariableValueTableRowsRow(
                  tableRowValueId = newValueId,
                  variableValueId = existingValueTableRow.variableValueId)
            }

        variableValueTableRowsDao.insert(newValueTableRows)
      }
    }

    val deletedId =
        insertValue(
            operation.documentId,
            maxListPosition,
            targetRowValueId ?: containingRowId,
            deletedValue)

    // If this was a table row, recursively delete all its column values.
    if (variablesRow.variableTypeId == VariableType.Table) {
      // Delete in reverse list position order so we don't waste time renumbering.
      val cellValueIds =
          dslContext
              .select(VARIABLE_VALUE_TABLE_ROWS.VARIABLE_VALUE_ID)
              .from(VARIABLE_VALUE_TABLE_ROWS)
              .join(VARIABLE_VALUES)
              .on(VARIABLE_VALUE_TABLE_ROWS.VARIABLE_VALUE_ID.eq(VARIABLE_VALUES.ID))
              .where(VARIABLE_VALUE_TABLE_ROWS.TABLE_ROW_VALUE_ID.eq(valueId))
              .and(VARIABLE_VALUES.IS_DELETED.isFalse)
              .orderBy(VARIABLE_VALUES.LIST_POSITION.desc())
              .fetch(VARIABLE_VALUE_TABLE_ROWS.VARIABLE_VALUE_ID.asNonNullable())

      cellValueIds.forEach { cellValueId ->
        deleteValue(DeleteValueOperation(operation.documentId, cellValueId), deletedId)
      }
    }
  }

  private fun replaceValues(
      operation: ReplaceValuesOperation,
      manifestId: VariableManifestId,
  ) {
    val variableId = operation.variableId
    val variablesRow =
        variablesDao.fetchOneById(variableId) ?: throw VariableNotFoundException(variableId)

    if (operation.values.size > 1 && variablesRow.isList != true) {
      throw VariableNotListException(variableId)
    }

    if (!isVariableInManifest(variableId, manifestId)) {
      throw VariableNotInManifestException(variableId, manifestId)
    }

    if (variablesRow.isList == true) {
      // If the replacement list has fewer items than the existing value, delete the items whose
      // list positions no longer exist.

      val maxListPosition =
          fetchMaxListPosition(operation.documentId, variableId, operation.rowValueId)
      if (maxListPosition != null) {
        (operation.values.size..maxListPosition).forEach { listPosition ->
          val deletedValue =
              DeletedValue(
                  BaseVariableValueProperties(
                      citation = null,
                      documentId = operation.documentId,
                      id = null,
                      listPosition = listPosition,
                      rowValueId = operation.rowValueId,
                      variableId = operation.variableId,
                  ),
                  variablesRow.variableTypeId!!)

          insertValue(operation.documentId, listPosition, operation.rowValueId, deletedValue)
        }
      }
    }

    operation.values.forEachIndexed { listPosition, value ->
      insertValue(operation.documentId, listPosition, operation.rowValueId, value)
    }
  }

  private fun updateValue(
      operation: UpdateValueOperation,
      manifestId: VariableManifestId,
  ) {
    val variableId = fetchVariableId(operation.value.id)
    if (!isVariableInManifest(variableId, manifestId)) {
      throw VariableNotInManifestException(variableId, manifestId)
    }

    val rowValueId = fetchContainingRowId(operation.value.id)

    // Updating images is a special case, because we need to carry the file ID over from the
    // existing value.
    val effectiveValue =
        if (operation.value is ImageValue<*>) {
          val existingImageValue =
              variableImageValuesDao.fetchOneByVariableValueId(operation.value.id)
                  ?: throw VariableValueIncompleteException(operation.value.id)
          ExistingImageValue(
              BaseVariableValueProperties(
                  citation = operation.value.citation,
                  documentId = operation.value.documentId,
                  id = operation.value.id,
                  listPosition = operation.value.listPosition,
                  rowValueId = operation.value.rowValueId,
                  variableId = operation.value.variableId,
              ),
              ImageValueDetails(operation.value.value.caption, existingImageValue.fileId!!))
        } else {
          operation.value
        }

    insertValue(operation.documentId, operation.value.listPosition, rowValueId, effectiveValue)
  }

  private fun insertValue(
      documentId: DocumentId,
      listPosition: Int,
      rowValueId: VariableValueId?,
      value: VariableValue<*, *>,
  ): VariableValueId {
    val valuesRow =
        VariableValuesRow(
            citation = value.citation,
            createdBy = currentUser().userId,
            createdTime = clock.instant(),
            dateValue = if (value is DateValue) value.value else null,
            documentId = documentId,
            isDeleted = value is DeletedValue,
            listPosition = listPosition,
            numberValue = if (value is NumberValue) value.value else null,
            textValue = if (value is TextValue) value.value else null,
            variableId = value.variableId,
            variableTypeId = value.type,
        )

    variableValuesDao.insert(valuesRow)

    val valueId = valuesRow.id!!

    if (rowValueId != null) {
      variableValueTableRowsDao.insert(
          VariableValueTableRowsRow(tableRowValueId = rowValueId, variableValueId = valueId))
    }

    when (value) {
      is DeletedValue,
      is NumberValue,
      is TextValue,
      is DateValue,
      is TableValue ->
          // Some values don't require any rows in child tables.
          Unit
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
        ))
  }

  private fun insertLinkValue(valueId: VariableValueId, value: LinkValue<*>) {
    variableLinkValuesDao.insert(
        VariableLinkValuesRow(
            title = value.value.title,
            url = value.value.url.toString(),
            variableId = value.variableId,
            variableTypeId = VariableType.Link,
            variableValueId = valueId,
        ))
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
      documentId: DocumentId,
      variableId: VariableId,
      rowValueId: VariableValueId?
  ): Int? {
    val deletedValues = VARIABLE_VALUES.`as`("deleted_values")
    val deletedRows = VARIABLE_VALUE_TABLE_ROWS.`as`("deleted_rows")

    return if (rowValueId == null) {
      dslContext
          .select(DSL.max(VARIABLE_VALUES.LIST_POSITION))
          .from(VARIABLE_VALUES)
          .where(VARIABLE_VALUES.VARIABLE_ID.eq(variableId))
          .and(VARIABLE_VALUES.DOCUMENT_ID.eq(documentId))
          .and(VARIABLE_VALUES.IS_DELETED.isFalse)
          .andNotExists(
              DSL.selectOne()
                  .from(deletedValues)
                  .where(deletedValues.VARIABLE_ID.eq(variableId))
                  .and(deletedValues.DOCUMENT_ID.eq(documentId))
                  .and(deletedValues.LIST_POSITION.eq(VARIABLE_VALUES.LIST_POSITION))
                  .and(deletedValues.IS_DELETED.isTrue)
                  .and(deletedValues.ID.gt(VARIABLE_VALUES.ID)))
          .fetchOne()
          ?.value1()
    } else {
      dslContext
          .select(DSL.max(VARIABLE_VALUES.LIST_POSITION))
          .from(VARIABLE_VALUES)
          .join(VARIABLE_VALUE_TABLE_ROWS)
          .on(VARIABLE_VALUES.ID.eq(VARIABLE_VALUE_TABLE_ROWS.VARIABLE_VALUE_ID))
          .where(VARIABLE_VALUES.VARIABLE_ID.eq(variableId))
          .and(VARIABLE_VALUES.DOCUMENT_ID.eq(documentId))
          .and(VARIABLE_VALUE_TABLE_ROWS.TABLE_ROW_VALUE_ID.eq(rowValueId))
          .and(VARIABLE_VALUES.IS_DELETED.isFalse)
          .andNotExists(
              DSL.selectOne()
                  .from(deletedValues)
                  .join(deletedRows)
                  .on(deletedValues.ID.eq(deletedRows.VARIABLE_VALUE_ID))
                  .where(deletedValues.VARIABLE_ID.eq(variableId))
                  .and(deletedValues.DOCUMENT_ID.eq(documentId))
                  .and(deletedRows.TABLE_ROW_VALUE_ID.eq(rowValueId))
                  .and(deletedValues.LIST_POSITION.eq(VARIABLE_VALUES.LIST_POSITION))
                  .and(deletedValues.IS_DELETED.isTrue)
                  .and(deletedValues.ID.gt(VARIABLE_VALUES.ID)))
          .fetchOne()
          ?.value1()
    }
  }

  private fun fetchNextListPosition(
      documentId: DocumentId,
      variableId: VariableId,
      rowValueId: VariableValueId?
  ): Int {
    return fetchMaxListPosition(documentId, variableId, rowValueId)?.let { it + 1 } ?: 0
  }

  private fun fetchDocumentId(valueId: VariableValueId): DocumentId {
    return with(VARIABLE_VALUES) {
      dslContext
          .select(DOCUMENT_ID)
          .from(VARIABLE_VALUES)
          .where(ID.eq(valueId))
          .fetchOne()
          ?.value1() ?: throw VariableValueNotFoundException(valueId)
    }
  }

  private fun isVariableInManifest(
      variableId: VariableId,
      manifestId: VariableManifestId
  ): Boolean {
    return dslContext
        .selectOne()
        .from(VARIABLE_MANIFEST_ENTRIES)
        .where(VARIABLE_MANIFEST_ENTRIES.VARIABLE_MANIFEST_ID.eq(manifestId))
        .and(VARIABLE_MANIFEST_ENTRIES.VARIABLE_ID.eq(variableId))
        .fetchOne() != null
  }
}
