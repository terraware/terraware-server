package com.terraformation.backend.documentproducer

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.db.docprod.VariableValueId
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.db.VariableValueStore
import com.terraformation.backend.documentproducer.model.AppendValueOperation
import com.terraformation.backend.documentproducer.model.BaseVariableValueProperties
import com.terraformation.backend.documentproducer.model.NewNumberValue
import com.terraformation.backend.documentproducer.model.NewTableValue
import com.terraformation.backend.documentproducer.model.NewTextValue
import com.terraformation.backend.documentproducer.model.ValueOperation
import com.terraformation.backend.mockUser
import io.mockk.every
import java.math.BigDecimal
import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class VariableUpgradeCalculatorTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()

  private val variableStore: VariableStore by lazy {
    VariableStore(
        dslContext,
        variableNumbersDao,
        variablesDao,
        variableSectionDefaultValuesDao,
        variableSectionRecommendationsDao,
        variableSectionsDao,
        variableSelectsDao,
        variableSelectOptionsDao,
        variableTablesDao,
        variableTableColumnsDao,
        variableTextsDao,
    )
  }
  private val variableValueStore: VariableValueStore by lazy {
    VariableValueStore(
        clock,
        dslContext,
        TestEventPublisher(),
        variableImageValuesDao,
        variableLinkValuesDao,
        variablesDao,
        variableSectionValuesDao,
        variableSelectOptionValuesDao,
        variableValuesDao,
        variableValueTableRowsDao,
    )
  }

  private val stableId = "${UUID.randomUUID()}"

  private lateinit var deliverableId: DeliverableId

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertProject()
    insertModule()
    deliverableId = insertDeliverable()

    every { user.canReadProjectDeliverables(any()) } returns true
  }

  @Test
  fun `does not return operations for values that no longer validate`() {
    val oldVariableId = insertNumberVariable(deliverableId = deliverableId, stableId = stableId)

    insertValue(variableId = oldVariableId, numberValue = BigDecimal.ONE)
    val newVariableId =
        insertNumberVariable(
            insertVariable(
                type = VariableType.Number,
                replacesVariableId = oldVariableId,
                deliverableId = deliverableId,
                stableId = stableId,
            ),
            minValue = BigDecimal.TEN,
        )

    assertEquals(
        emptyList<ValueOperation>(),
        calculateOperations(mapOf(oldVariableId to newVariableId)),
    )
  }

  // This just tests that values are converted as part of the upgrade calculations; the conversion
  // logic is covered in VariableTest.
  @Test
  fun `converts value to new variable type`() {
    val oldVariableId =
        insertNumberVariable(decimalPlaces = 5, deliverableId = deliverableId, stableId = stableId)
    insertValue(variableId = oldVariableId, numberValue = BigDecimal("1.23456"))

    val newVariableId =
        insertTextVariable(
            insertVariable(
                type = VariableType.Text,
                replacesVariableId = oldVariableId,
                deliverableId = deliverableId,
                stableId = stableId,
            )
        )

    assertEquals(
        listOf(AppendValueOperation(NewTextValue(newValueProps(newVariableId), "1.23456"))),
        calculateOperations(mapOf(oldVariableId to newVariableId)),
    )
  }

  @Test
  fun `appends valid list items in same order as original list`() {
    val oldVariableId =
        insertNumberVariable(
            insertVariable(
                type = VariableType.Number,
                isList = true,
                deliverableId = deliverableId,
                stableId = stableId,
            )
        )

    insertValue(variableId = oldVariableId, listPosition = 0, numberValue = BigDecimal(0))
    insertValue(variableId = oldVariableId, listPosition = 1, numberValue = BigDecimal(1))
    insertValue(variableId = oldVariableId, listPosition = 2, numberValue = BigDecimal(100))
    insertValue(variableId = oldVariableId, listPosition = 3, numberValue = BigDecimal(3))

    val newVariableId =
        insertNumberVariable(
            insertVariable(
                type = VariableType.Number,
                isList = true,
                replacesVariableId = oldVariableId,
                deliverableId = deliverableId,
                stableId = stableId,
            ),
            maxValue = BigDecimal.TEN,
        )

    assertEquals(
        listOf(
            AppendValueOperation(
                NewNumberValue(newValueProps(newVariableId, listPosition = 0), BigDecimal(0))
            ),
            AppendValueOperation(
                NewNumberValue(newValueProps(newVariableId, listPosition = 1), BigDecimal(1))
            ),
            // List position is preserved across value conversion, so we expect to skip position 2
            // here; it is ignored by append operations so this won't result in a hole in the final
            // result.
            AppendValueOperation(
                NewNumberValue(newValueProps(newVariableId, listPosition = 3), BigDecimal(3))
            ),
        ),
        calculateOperations(mapOf(oldVariableId to newVariableId)),
    )
  }

  @Test
  fun `populates new table rows with values from old columns`() {
    // Original table has one obsolete column that won't appear in the new table version, and one
    // outdated column that will appear, but with different settings (a minimum value).
    val oldTableVariableId =
        insertTableVariable(deliverableId = deliverableId, stableId = "table-$stableId")
    val oldTableObsoleteColumnId =
        insertTextVariable(deliverableId = deliverableId, stableId = "col1-$stableId")
    insertTableColumn(oldTableVariableId, oldTableObsoleteColumnId)
    val oldTableOutdatedColumnId =
        insertNumberVariable(deliverableId = deliverableId, stableId = "col2-$stableId")
    insertTableColumn(oldTableVariableId, oldTableOutdatedColumnId)

    val newTableVariableId =
        insertTableVariable(
            insertVariable(
                type = VariableType.Table,
                replacesVariableId = oldTableVariableId,
                deliverableId = deliverableId,
                stableId = "table-$stableId",
            )
        )
    val newTableUpdatedColumnId =
        insertNumberVariable(
            insertVariable(
                type = VariableType.Number,
                replacesVariableId = oldTableOutdatedColumnId,
                deliverableId = deliverableId,
                stableId = "col2-$stableId",
            ),
            minValue = BigDecimal.TEN,
        )
    insertTableColumn(newTableVariableId, newTableUpdatedColumnId)

    val oldTableRowId1 = insertValue(variableId = oldTableVariableId, listPosition = 0)
    val oldRowVal1Col1 =
        insertValue(variableId = oldTableObsoleteColumnId, textValue = "obsolete 1")
    insertValueTableRow(oldRowVal1Col1, oldTableRowId1)
    val oldRowVal1Col2 =
        insertValue(
            variableId = oldTableOutdatedColumnId,
            numberValue = BigDecimal(50),
            citation = "citation",
        )
    insertValueTableRow(oldRowVal1Col2, oldTableRowId1)

    val oldTableRowId2 = insertValue(variableId = oldTableVariableId, listPosition = 1)
    val oldRowVal2Col1 =
        insertValue(variableId = oldTableObsoleteColumnId, textValue = "obsolete 2")
    insertValueTableRow(oldRowVal2Col1, oldTableRowId2)
    val oldRowVal2Col2 =
        insertValue(variableId = oldTableOutdatedColumnId, numberValue = BigDecimal(5))
    insertValueTableRow(oldRowVal2Col2, oldTableRowId2)

    assertEquals(
        listOf(
            // Append new rows
            AppendValueOperation(NewTableValue(newValueProps(newTableVariableId))),
            AppendValueOperation(
                NewNumberValue(
                    newValueProps(newTableUpdatedColumnId, citation = "citation"),
                    BigDecimal(50),
                )
            ),
            // Empty row because the old one didn't have any values to carry forward.
            AppendValueOperation(NewTableValue(newValueProps(newTableVariableId))),
        ),
        calculateOperations(
            mapOf(
                oldTableVariableId to newTableVariableId,
                oldTableOutdatedColumnId to newTableUpdatedColumnId,
            )
        ),
    )
  }

  @Test
  fun `populates value for variable that moved from a different deliverable`() {
    val oldDeliverableVariableId =
        insertNumberVariable(deliverableId = deliverableId, stableId = stableId)
    insertValue(variableId = oldDeliverableVariableId, numberValue = BigDecimal(5))

    val newDeliverableId = insertDeliverable()
    val newDeliverableVariableId =
        insertNumberVariable(
            insertVariable(
                type = VariableType.Number,
                deliverableId = newDeliverableId,
                replacesVariableId = oldDeliverableVariableId,
                stableId = stableId,
            )
        )

    assertEquals(
        listOf(
            AppendValueOperation(
                NewNumberValue(newValueProps(newDeliverableVariableId), BigDecimal(5))
            )
        ),
        calculateOperations(mapOf(oldDeliverableVariableId to newDeliverableVariableId)),
    )
  }

  @Test
  fun `populates values across all projects`() {
    val projectId1 = inserted.projectId
    val projectId2 = insertProject()

    val oldVariableId =
        insertNumberVariable(decimalPlaces = 5, deliverableId = deliverableId, stableId = stableId)
    insertValue(variableId = oldVariableId, projectId = projectId1, numberValue = BigDecimal(1))
    insertValue(variableId = oldVariableId, projectId = projectId2, numberValue = BigDecimal(2))

    val newVariableId =
        insertNumberVariable(
            insertVariable(
                type = VariableType.Number,
                replacesVariableId = oldVariableId,
                deliverableId = deliverableId,
                stableId = stableId,
            )
        )

    assertEquals(
        listOf(
            AppendValueOperation(
                NewNumberValue(newValueProps(newVariableId, projectId1), BigDecimal(1))
            ),
            AppendValueOperation(
                NewNumberValue(newValueProps(newVariableId, projectId2), BigDecimal(2))
            ),
        ),
        calculateOperations(mapOf(oldVariableId to newVariableId)),
    )
  }

  @Test
  fun `only populates values for projects that do not already have values for new variables`() {
    val projectId1 = inserted.projectId
    val projectId2 = insertProject()
    val projectId3 = insertProject()

    val oldVariableId =
        insertNumberVariable(decimalPlaces = 5, deliverableId = deliverableId, stableId = stableId)
    insertValue(variableId = oldVariableId, projectId = projectId1, numberValue = BigDecimal(1))
    insertValue(variableId = oldVariableId, projectId = projectId2, numberValue = BigDecimal(2))
    insertValue(variableId = oldVariableId, projectId = projectId3, numberValue = BigDecimal(3))

    val newVariableId =
        insertNumberVariable(
            insertVariable(
                type = VariableType.Number,
                replacesVariableId = oldVariableId,
                deliverableId = deliverableId,
                stableId = stableId,
            )
        )
    insertValue(variableId = newVariableId, projectId = projectId2, numberValue = BigDecimal.ZERO)

    assertEquals(
        listOf(
            AppendValueOperation(
                NewNumberValue(newValueProps(newVariableId, projectId1), BigDecimal(1))
            ),
            AppendValueOperation(
                NewNumberValue(newValueProps(newVariableId, projectId3), BigDecimal(3))
            ),
        ),
        calculateOperations(mapOf(oldVariableId to newVariableId)),
    )
  }

  private fun calculateOperations(replacements: Map<VariableId, VariableId>) =
      VariableUpgradeCalculator(replacements, variableStore, variableValueStore)
          .calculateOperations()

  private fun newValueProps(
      variableId: VariableId,
      projectId: ProjectId = inserted.projectId,
      listPosition: Int = 0,
      rowValueId: VariableValueId? = null,
      citation: String? = null,
  ): BaseVariableValueProperties<Nothing?> {
    return BaseVariableValueProperties(
        citation = citation,
        id = null,
        listPosition = listPosition,
        projectId = projectId,
        variableId = variableId,
        rowValueId = rowValueId,
    )
  }
}
