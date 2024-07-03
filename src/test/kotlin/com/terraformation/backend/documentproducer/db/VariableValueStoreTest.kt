package com.terraformation.backend.documentproducer.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.db.docprod.VariableValueId
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_VALUES
import com.terraformation.backend.documentproducer.event.QuestionsDeliverableSubmittedEvent
import com.terraformation.backend.documentproducer.model.AppendValueOperation
import com.terraformation.backend.documentproducer.model.BaseVariableValueProperties
import com.terraformation.backend.documentproducer.model.DeleteValueOperation
import com.terraformation.backend.documentproducer.model.ExistingDeletedValue
import com.terraformation.backend.documentproducer.model.ExistingTableValue
import com.terraformation.backend.documentproducer.model.ExistingTextValue
import com.terraformation.backend.documentproducer.model.ExistingValue
import com.terraformation.backend.documentproducer.model.NewTableValue
import com.terraformation.backend.documentproducer.model.NewTextValue
import com.terraformation.backend.mockUser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class VariableValueStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  override val tablesToResetSequences = listOf(VARIABLE_VALUES)

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  private val store by lazy {
    VariableValueStore(
        clock,
        documentsDao,
        dslContext,
        eventPublisher,
        variableImageValuesDao,
        variableLinkValuesDao,
        variablesDao,
        variableSectionValuesDao,
        variableSelectOptionValuesDao,
        variableValuesDao,
        variableValueTableRowsDao)
  }

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertProject()
    insertDocumentTemplate()
    insertVariableManifest()
    insertDocument()
  }

  @Nested
  inner class ListValues {
    @Test
    fun `returns newest table cell value`() {
      val tableVariableId = insertVariableManifestEntry(insertTableVariable())
      val textVariableId =
          insertVariableManifestEntry(
              insertTextVariable(id = insertVariable(isList = true, type = VariableType.Text)))
      insertTableColumn(tableVariableId, textVariableId)

      val rowValueId = insertValue(variableId = tableVariableId)
      val initialTextValueId = insertValue(variableId = textVariableId, textValue = "Old")
      insertValueTableRow(initialTextValueId, rowValueId)

      val updatedTextValueId = insertValue(variableId = textVariableId, textValue = "New")
      insertValueTableRow(updatedTextValueId, rowValueId)

      val expected =
          listOf(
              ExistingTableValue(existingValueProps(rowValueId, tableVariableId)),
              ExistingTextValue(
                  existingValueProps(updatedTextValueId, textVariableId, rowValueId = rowValueId),
                  "New"))

      val actual = store.listValues(inserted.documentId)

      assertEquals(expected, actual)
    }

    @Test
    fun `returns deleted value on incremental query`() {
      val variableId =
          insertVariableManifestEntry(
              insertTextVariable(id = insertVariable(isList = true, type = VariableType.Text)))

      insertValue(variableId = variableId, textValue = "Old")
      val deletedValueId = insertValue(variableId = variableId, isDeleted = true)

      val expected =
          listOf(
              ExistingDeletedValue(
                  existingValueProps(deletedValueId, variableId), VariableType.Text))

      val actual = store.listValues(inserted.documentId, minValueId = deletedValueId)

      assertEquals(expected, actual)
    }

    @Test
    fun `omits deleted value on non-incremental query`() {
      val variableId =
          insertVariableManifestEntry(
              insertTextVariable(id = insertVariable(isList = true, type = VariableType.Text)))

      insertValue(variableId = variableId, textValue = "Old")
      insertValue(variableId = variableId, isDeleted = true)

      val expected = emptyList<ExistingValue>()

      val actual = store.listValues(inserted.documentId)

      assertEquals(expected, actual)
    }

    @Test
    fun `returns specific variable values for a given project`() {
      val projectId = insertProject()
      insertModule()
      val deliverableId1 = insertDeliverable()
      val deliverableId2 = insertDeliverable()

      val variableId1 =
          insertTextVariable(
              id =
                  insertVariable(
                      type = VariableType.Text,
                      deliverableId = deliverableId1,
                      deliverablePosition = 0))
      val valueId1 =
          insertValue(projectId = projectId, textValue = "value1", variableId = variableId1)
      val variableId2 =
          insertTextVariable(
              id =
                  insertVariable(
                      type = VariableType.Text,
                      deliverableId = deliverableId1,
                      deliverablePosition = 1))
      val valueId2 =
          insertValue(projectId = projectId, textValue = "value2", variableId = variableId2)
      val variableId3 =
          insertTextVariable(
              id =
                  insertVariable(
                      type = VariableType.Text,
                      deliverableId = deliverableId2,
                      deliverablePosition = 0))
      insertValue(textValue = "value3", variableId = variableId3)

      val expected =
          listOf(
              ExistingTextValue(existingValueProps(valueId1, variableId1), "value1"),
              ExistingTextValue(existingValueProps(valueId2, variableId2), "value2"))

      val actual = store.listValues(projectId, deliverableId1)

      assertEquals(expected, actual)
    }
  }

  @Nested
  inner class UpdateValues {
    @Nested
    inner class AppendValue {
      @Test
      fun `sets initial value of non-list variable`() {
        val variableId = insertVariableManifestEntry(insertTextVariable())

        val expected = listOf(ExistingTextValue(existingValueProps(1, variableId), "new"))

        val actual =
            store.updateValues(
                listOf(AppendValueOperation(NewTextValue(newValueProps(variableId), "new"))))

        assertEquals(expected, actual)
      }

      @Test
      fun `adds values to list variable`() {
        val variableId =
            insertVariableManifestEntry(
                insertTextVariable(id = insertVariable(isList = true, type = VariableType.Text)))

        val expected =
            listOf(
                ExistingTextValue(existingValueProps(1, variableId, position = 0), "first"),
                ExistingTextValue(existingValueProps(2, variableId, position = 1), "second"),
            )

        val actual =
            store.updateValues(
                listOf(
                    AppendValueOperation(NewTextValue(newValueProps(variableId), "first")),
                    AppendValueOperation(NewTextValue(newValueProps(variableId), "second")),
                ))

        assertEquals(expected, actual)
      }

      @Test
      fun `associates values with newly-inserted rows`() {
        val tableVariableId =
            insertVariableManifestEntry(
                insertTableVariable(insertVariable(isList = true, type = VariableType.Table)))
        val columnVariableId = insertVariableManifestEntry(insertTextVariable())
        insertTableColumn(tableVariableId, columnVariableId)

        val expected =
            listOf(
                // Value list is sorted by variable ID then by value ID, so table values come first
                ExistingTableValue(existingValueProps(1, tableVariableId)),
                ExistingTableValue(existingValueProps(3, tableVariableId, position = 1)),
                ExistingTextValue(
                    existingValueProps(2, columnVariableId, rowValueId = 1), "row 0 column"),
                ExistingTextValue(
                    existingValueProps(4, columnVariableId, rowValueId = 3), "row 1 column"),
            )

        val actual =
            store.updateValues(
                listOf(
                    AppendValueOperation(NewTableValue(newValueProps(tableVariableId))),
                    AppendValueOperation(
                        NewTextValue(newValueProps(columnVariableId), "row 0 column")),
                    AppendValueOperation(NewTableValue(newValueProps(tableVariableId))),
                    AppendValueOperation(
                        NewTextValue(newValueProps(columnVariableId), "row 1 column")),
                ))

        assertEquals(expected, actual)
      }

      @Test
      fun `publishes event with updated variable values if a deliverable is associated`() {
        insertModule()
        insertDeliverable()
        val variableId =
            insertVariableManifestEntry(
                insertTextVariable(
                    id =
                        insertVariable(
                            deliverableId = inserted.deliverableId,
                            deliverablePosition = 0,
                            type = VariableType.Text)))

        val updatedValues =
            store.updateValues(
                listOf(AppendValueOperation(NewTextValue(newValueProps(variableId), "new"))))

        eventPublisher.assertEventPublished(
            QuestionsDeliverableSubmittedEvent(
                inserted.deliverableId,
                inserted.projectId,
                updatedValues.associate { it.variableId to it.id }))
      }

      @Test
      fun `does not publish event if a deliverable is not associated`() {
        val variableId =
            insertVariableManifestEntry(
                insertTextVariable(
                    id = insertVariable(deliverableId = null, type = VariableType.Text)))
        store.updateValues(
            listOf(AppendValueOperation(NewTextValue(newValueProps(variableId), "new"))))

        eventPublisher.assertEventNotPublished<QuestionsDeliverableSubmittedEvent>()
      }
    }

    @Nested
    inner class DeleteValue {
      @Test
      fun `renumbers later table rows`() {
        val tableVariableId =
            insertVariableManifestEntry(
                insertTableVariable(insertVariable(isList = true, type = VariableType.Table)))
        val columnVariableId = insertVariableManifestEntry(insertTextVariable())
        insertTableColumn(tableVariableId, columnVariableId)

        val row0Id = insertValue(variableId = tableVariableId, listPosition = 0)
        val row0ColumnValueId = insertValue(variableId = columnVariableId, textValue = "A")
        insertValueTableRow(row0ColumnValueId, row0Id)
        val row1Id = insertValue(variableId = tableVariableId, listPosition = 1)
        val row1ColumnValueId = insertValue(variableId = columnVariableId, textValue = "B")
        insertValueTableRow(row1ColumnValueId, row1Id)
        val row2Id = insertValue(variableId = tableVariableId, listPosition = 2)
        val row2ColumnValueId = insertValue(variableId = columnVariableId, textValue = "C")
        insertValueTableRow(row2ColumnValueId, row2Id)

        store.updateValues(listOf(DeleteValueOperation(inserted.projectId, row0Id)))

        val updatedDocument = store.listValues(inserted.documentId)

        assertEquals(
            2, updatedDocument.count { it.type == VariableType.Table }, "Number of table rows")

        val newRow0Id =
            updatedDocument.single { it.type == VariableType.Table && it.listPosition == 0 }.id
        val newRow1Id =
            updatedDocument.single { it.type == VariableType.Table && it.listPosition == 1 }.id
        assertNotEquals(row0Id, newRow0Id, "ID of row at list position 0 after delete")
        assertNotEquals(row1Id, newRow0Id, "Row should not have same ID after renumbering")
        assertNotEquals(row1Id, newRow1Id, "ID of row at list position 1 after delete")

        assertEquals(
            row1ColumnValueId,
            updatedDocument.single { it.rowValueId == newRow0Id }.id,
            "ID of column value in new row 0")
        assertEquals(
            row2ColumnValueId,
            updatedDocument.single { it.rowValueId == newRow1Id }.id,
            "ID of column value in new row 1")
      }

      @Test
      fun `can delete multiple table rows`() {
        val tableVariableId =
            insertVariableManifestEntry(
                insertTableVariable(insertVariable(isList = true, type = VariableType.Table)))
        val columnVariableId = insertVariableManifestEntry(insertTextVariable())
        insertTableColumn(tableVariableId, columnVariableId)

        val row0Id = insertValue(variableId = tableVariableId, listPosition = 0)
        val row0ColumnValueId = insertValue(variableId = columnVariableId, textValue = "A")
        insertValueTableRow(row0ColumnValueId, row0Id)
        val row1Id = insertValue(variableId = tableVariableId, listPosition = 1)
        val row1ColumnValueId = insertValue(variableId = columnVariableId, textValue = "B")
        insertValueTableRow(row1ColumnValueId, row1Id)
        val row2Id = insertValue(variableId = tableVariableId, listPosition = 2)
        val row2ColumnValueId = insertValue(variableId = columnVariableId, textValue = "C")
        insertValueTableRow(row2ColumnValueId, row2Id)

        store.updateValues(
            listOf(
                DeleteValueOperation(inserted.projectId, row1Id),
                DeleteValueOperation(inserted.projectId, row0Id)))

        val updatedDocument = store.listValues(inserted.documentId)

        assertEquals(
            1, updatedDocument.count { it.type == VariableType.Table }, "Number of table rows")

        val newRow0Id =
            updatedDocument.single { it.type == VariableType.Table && it.listPosition == 0 }.id
        assertEquals(
            row2ColumnValueId,
            updatedDocument.single { it.rowValueId == newRow0Id }.id,
            "ID of column value in new row 0")
      }

      @Test
      fun `repeatedly adding and deleting values results in correct list positions`() {
        val variableId =
            insertVariableManifestEntry(
                insertTextVariable(insertVariable(isList = true, type = VariableType.Text)))

        val append1Result =
            store.updateValues(
                listOf(
                    AppendValueOperation(NewTextValue(newValueProps(variableId), "1")),
                    AppendValueOperation(NewTextValue(newValueProps(variableId), "2"))))
        store.updateValues(
            listOf(
                DeleteValueOperation(
                    inserted.projectId, append1Result.first { it.value == "1" }.id),
                AppendValueOperation(NewTextValue(newValueProps(variableId), "3"))))

        val round1Values = store.listValues(inserted.documentId)
        assertEquals(
            listOf(0 to "2", 1 to "3"),
            round1Values.map { it.listPosition to it.value },
            "Values after append, append, delete, append")

        store.updateValues(
            listOf(
                DeleteValueOperation(inserted.projectId, round1Values.first { it.value == "2" }.id),
                AppendValueOperation(NewTextValue(newValueProps(variableId), "4"))))

        val round2Values = store.listValues(inserted.documentId)
        assertEquals(
            listOf(0 to "3", 1 to "4"),
            round2Values.map { it.listPosition to it.value },
            "Values after append, append, delete, append, delete, append")
      }
    }
  }

  @Nested
  inner class FetchNextListPosition {
    @Test
    fun `returns next available position for list`() {
      val variableId =
          insertVariableManifestEntry(insertVariable(type = VariableType.Text, isList = true))
      insertValue(variableId = variableId, listPosition = 0, textValue = "first")
      insertValue(variableId = variableId, listPosition = 1, textValue = "second")

      assertEquals(2, store.fetchNextListPosition(inserted.projectId, variableId))
    }

    @Test
    fun `returns 0 if there are no existing values`() {
      val variableId =
          insertVariableManifestEntry(
              insertTextVariable(insertVariable(type = VariableType.Text, isList = true)))

      assertEquals(0, store.fetchNextListPosition(inserted.projectId, variableId))
    }

    @Test
    fun `returns 0 if the variable is not a list`() {
      val variableId = insertVariableManifestEntry(insertTextVariable())
      insertValue(variableId = variableId, textValue = "text")

      assertEquals(0, store.fetchNextListPosition(inserted.projectId, variableId))
    }

    @Test
    fun `ignores deleted values`() {
      val variableId =
          insertVariableManifestEntry(insertVariable(type = VariableType.Text, isList = true))
      insertValue(variableId = variableId, listPosition = 0, textValue = "not deleted")
      insertValue(
          variableId = variableId, listPosition = 1, textValue = "deleted", isDeleted = true)

      assertEquals(1, store.fetchNextListPosition(inserted.projectId, variableId))
    }
  }

  private fun existingValueProps(
      valueId: Any,
      variableId: Any,
      projectId: ProjectId = inserted.projectId,
      position: Int = 0,
      rowValueId: Any? = null,
      citation: String? = null,
  ): BaseVariableValueProperties<VariableValueId> {
    return BaseVariableValueProperties(
        citation = citation,
        id = valueId.toIdWrapper { VariableValueId(it) },
        listPosition = position,
        projectId = projectId,
        variableId = variableId.toIdWrapper { VariableId(it) },
        rowValueId = rowValueId?.toIdWrapper { VariableValueId(it) })
  }

  private fun newValueProps(
      variableId: Any,
      projectId: ProjectId = inserted.projectId,
      position: Int = 0,
      rowValueId: Any? = null,
      citation: String? = null,
  ): BaseVariableValueProperties<Nothing?> {
    return BaseVariableValueProperties(
        citation = citation,
        id = null,
        listPosition = position,
        projectId = projectId,
        variableId = variableId.toIdWrapper { VariableId(it) },
        rowValueId = rowValueId?.toIdWrapper { VariableValueId(it) })
  }
}
