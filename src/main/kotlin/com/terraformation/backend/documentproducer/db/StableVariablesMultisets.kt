package com.terraformation.backend.documentproducer.db

import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.db.docprod.tables.references.VARIABLES
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_IMAGE_VALUES
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_LINK_VALUES
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_SECTION_VALUES
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_SELECT_OPTION_VALUES
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_VALUES
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_VALUE_TABLE_ROWS
import com.terraformation.backend.documentproducer.model.BaseVariableValueProperties
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
import com.terraformation.backend.documentproducer.model.ImageValueDetails
import com.terraformation.backend.documentproducer.model.LinkValueDetails
import com.terraformation.backend.documentproducer.model.SectionValueText
import com.terraformation.backend.documentproducer.model.SectionValueVariable
import java.net.URI
import org.jooq.Field
import org.jooq.impl.DSL

fun stableVariableValuesMultiset(
    projectIdField: Field<ProjectId>,
    stableIds: Set<StableId>,
    includeInternalVariables: Boolean = false,
): Field<Map<StableId, ExistingValue>> {
  val selectOptionsMultiset =
      DSL.multiset(
              DSL.select(VARIABLE_SELECT_OPTION_VALUES.OPTION_ID)
                  .from(VARIABLE_SELECT_OPTION_VALUES)
                  .where(VARIABLE_SELECT_OPTION_VALUES.VARIABLE_VALUE_ID.eq(VARIABLE_VALUES.ID)))
          .convertFrom { result ->
            result.map { record -> record[VARIABLE_SELECT_OPTION_VALUES.OPTION_ID] }.toSet()
          }

  val tableRowVariableValues = VARIABLE_VALUES.`as`("table_row_values")

  return DSL.multiset(
          DSL.select(
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
                  VARIABLES.STABLE_ID,
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
              .where(VARIABLE_VALUES.PROJECT_ID.asNonNullable().eq(projectIdField))
              .and(VARIABLES.STABLE_ID.`in`(stableIds))
              .apply {
                if (!includeInternalVariables) {
                  this.and(VARIABLES.INTERNAL_ONLY.eq(false))
                }
              }
              .orderBy(
                  VARIABLE_VALUES.PROJECT_ID,
                  VARIABLE_VALUES.VARIABLE_ID,
                  VARIABLE_VALUES.LIST_POSITION,
                  tableRowVariableValues.VARIABLE_ID,
                  tableRowVariableValues.LIST_POSITION,
                  tableRowVariableValues.ID.desc(),
                  VARIABLE_VALUES.ID.desc(),
              ))
      .convertFrom { result ->
        result.associate { record ->
          val base =
              BaseVariableValueProperties(
                  citation = record[VARIABLE_VALUES.CITATION],
                  projectId = record[VARIABLE_VALUES.PROJECT_ID]!!,
                  id = record[VARIABLE_VALUES.ID]!!,
                  listPosition = record[VARIABLE_VALUES.LIST_POSITION]!!,
                  rowValueId = record[VARIABLE_VALUE_TABLE_ROWS.TABLE_ROW_VALUE_ID],
                  variableId = record[VARIABLE_VALUES.VARIABLE_ID]!!,
              )

          val variableStableId = record[VARIABLES.STABLE_ID]!!
          val variableType = record[VARIABLE_VALUES.VARIABLE_TYPE_ID]!!

          val value =
              if (record[VARIABLE_VALUES.IS_DELETED] == true) {
                ExistingDeletedValue(base, variableType)
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

          variableStableId to value
        }
      }
}
