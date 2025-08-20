package com.terraformation.backend.documentproducer.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.event.VariableValueUpdatedEvent
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableInjectionDisplayStyle
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.db.docprod.VariableUsageType
import com.terraformation.backend.db.docprod.VariableValueId
import com.terraformation.backend.db.docprod.VariableWorkflowStatus
import com.terraformation.backend.documentproducer.event.CompletedSectionVariableUpdatedEvent
import com.terraformation.backend.documentproducer.model.AppendValueOperation
import com.terraformation.backend.documentproducer.model.BaseVariableValueProperties
import com.terraformation.backend.documentproducer.model.DeleteValueOperation
import com.terraformation.backend.documentproducer.model.ExistingDeletedValue
import com.terraformation.backend.documentproducer.model.ExistingTableValue
import com.terraformation.backend.documentproducer.model.ExistingTextValue
import com.terraformation.backend.documentproducer.model.ExistingValue
import com.terraformation.backend.documentproducer.model.ImageValueDetails
import com.terraformation.backend.documentproducer.model.NewImageValue
import com.terraformation.backend.documentproducer.model.NewTableValue
import com.terraformation.backend.documentproducer.model.NewTextValue
import com.terraformation.backend.documentproducer.model.SectionValueVariable
import com.terraformation.backend.mockUser
import io.mockk.every
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class VariableValueStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  private val store by lazy {
    VariableValueStore(
        clock,
        dslContext,
        eventPublisher,
        variableImageValuesDao,
        variableLinkValuesDao,
        variablesDao,
        variableSectionValuesDao,
        variableSelectOptionValuesDao,
        variableValuesDao,
        variableValueTableRowsDao,
    )
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
              insertTextVariable(id = insertVariable(isList = true, type = VariableType.Text))
          )
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
                  "New",
              ),
          )

      val actual = store.listValues(inserted.documentId)

      assertEquals(expected, actual)
    }

    @Test
    fun `returns deleted value on incremental query`() {
      val variableId =
          insertVariableManifestEntry(
              insertTextVariable(id = insertVariable(isList = true, type = VariableType.Text))
          )

      insertValue(variableId = variableId, textValue = "Old")
      val deletedValueId = insertValue(variableId = variableId, isDeleted = true)

      val expected =
          listOf(
              ExistingDeletedValue(
                  existingValueProps(deletedValueId, variableId),
                  VariableType.Text,
              )
          )

      val actual = store.listValues(inserted.documentId, minValueId = deletedValueId)

      assertEquals(expected, actual)
    }

    @Test
    fun `omits deleted value on non-incremental query`() {
      val variableId =
          insertVariableManifestEntry(
              insertTextVariable(id = insertVariable(isList = true, type = VariableType.Text))
          )

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

      val variableId1 = insertTextVariable(deliverableId = deliverableId1)
      val valueId1 =
          insertValue(projectId = projectId, textValue = "value1", variableId = variableId1)
      val variableId2 = insertTextVariable(deliverableId = deliverableId1)

      val valueId2 =
          insertValue(projectId = projectId, textValue = "value2", variableId = variableId2)
      val variableId3 = insertTextVariable(deliverableId = deliverableId2)

      insertValue(textValue = "value3", variableId = variableId3)

      val expected =
          listOf(
              ExistingTextValue(existingValueProps(valueId1, variableId1), "value1"),
              ExistingTextValue(existingValueProps(valueId2, variableId2), "value2"),
          )

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

        val actual =
            store.updateValues(
                listOf(AppendValueOperation(NewTextValue(newValueProps(variableId), "new")))
            )

        val expected =
            listOf(ExistingTextValue(existingValueProps(actual[0].id, variableId), "new"))

        assertEquals(expected, actual)
      }

      @Test
      fun `adds values to list variable`() {
        val variableId =
            insertVariableManifestEntry(
                insertTextVariable(id = insertVariable(isList = true, type = VariableType.Text))
            )

        val actual =
            store.updateValues(
                listOf(
                    AppendValueOperation(NewTextValue(newValueProps(variableId), "first")),
                    AppendValueOperation(NewTextValue(newValueProps(variableId), "second")),
                )
            )

        val expected =
            listOf(
                ExistingTextValue(
                    existingValueProps(actual[0].id, variableId, position = 0),
                    "first",
                ),
                ExistingTextValue(
                    existingValueProps(actual[1].id, variableId, position = 1),
                    "second",
                ),
            )

        assertEquals(expected, actual)
      }

      @Test
      fun `associates values with newly-inserted rows`() {
        val tableVariableId =
            insertVariableManifestEntry(
                insertTableVariable(insertVariable(isList = true, type = VariableType.Table))
            )
        val columnVariableId = insertVariableManifestEntry(insertTextVariable())
        insertTableColumn(tableVariableId, columnVariableId)

        val actual =
            store.updateValues(
                listOf(
                    AppendValueOperation(NewTableValue(newValueProps(tableVariableId))),
                    AppendValueOperation(
                        NewTextValue(newValueProps(columnVariableId), "row 0 column")
                    ),
                    AppendValueOperation(NewTableValue(newValueProps(tableVariableId))),
                    AppendValueOperation(
                        NewTextValue(newValueProps(columnVariableId), "row 1 column")
                    ),
                )
            )

        val expected =
            listOf(
                // Value list is sorted by variable ID then by value ID, so table values come first
                ExistingTableValue(existingValueProps(actual[0].id, tableVariableId)),
                ExistingTableValue(existingValueProps(actual[1].id, tableVariableId, position = 1)),
                ExistingTextValue(
                    existingValueProps(actual[2].id, columnVariableId, rowValueId = actual[0].id),
                    "row 0 column",
                ),
                ExistingTextValue(
                    existingValueProps(actual[3].id, columnVariableId, rowValueId = actual[1].id),
                    "row 1 column",
                ),
            )

        assertEquals(expected, actual)
      }
    }

    @Nested
    inner class DeleteValue {
      @Test
      fun `renumbers later table rows`() {
        val tableVariableId =
            insertVariableManifestEntry(
                insertTableVariable(insertVariable(isList = true, type = VariableType.Table))
            )
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
            2,
            updatedDocument.count { it.type == VariableType.Table },
            "Number of table rows",
        )

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
            "ID of column value in new row 0",
        )
        assertEquals(
            row2ColumnValueId,
            updatedDocument.single { it.rowValueId == newRow1Id }.id,
            "ID of column value in new row 1",
        )
      }

      @Test
      fun `can delete multiple table rows`() {
        val tableVariableId =
            insertVariableManifestEntry(
                insertTableVariable(insertVariable(isList = true, type = VariableType.Table))
            )
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
                DeleteValueOperation(inserted.projectId, row0Id),
            )
        )

        val updatedDocument = store.listValues(inserted.documentId)

        assertEquals(
            1,
            updatedDocument.count { it.type == VariableType.Table },
            "Number of table rows",
        )

        val newRow0Id =
            updatedDocument.single { it.type == VariableType.Table && it.listPosition == 0 }.id
        assertEquals(
            row2ColumnValueId,
            updatedDocument.single { it.rowValueId == newRow0Id }.id,
            "ID of column value in new row 0",
        )
      }

      @Test
      fun `repeatedly adding and deleting values results in correct list positions`() {
        val variableId =
            insertVariableManifestEntry(
                insertTextVariable(insertVariable(isList = true, type = VariableType.Text))
            )

        val append1Result =
            store.updateValues(
                listOf(
                    AppendValueOperation(NewTextValue(newValueProps(variableId), "1")),
                    AppendValueOperation(NewTextValue(newValueProps(variableId), "2")),
                )
            )
        store.updateValues(
            listOf(
                DeleteValueOperation(
                    inserted.projectId,
                    append1Result.first { it.value == "1" }.id,
                ),
                AppendValueOperation(NewTextValue(newValueProps(variableId), "3")),
            )
        )

        val round1Values = store.listValues(inserted.documentId)
        assertEquals(
            listOf(0 to "2", 1 to "3"),
            round1Values.map { it.listPosition to it.value },
            "Values after append, append, delete, append",
        )

        store.updateValues(
            listOf(
                DeleteValueOperation(inserted.projectId, round1Values.first { it.value == "2" }.id),
                AppendValueOperation(NewTextValue(newValueProps(variableId), "4")),
            )
        )

        val round2Values = store.listValues(inserted.documentId)
        assertEquals(
            listOf(0 to "3", 1 to "4"),
            round2Values.map { it.listPosition to it.value },
            "Values after append, append, delete, append, delete, append",
        )
      }

      @Test
      fun `deleting an already-deleted value does nothing`() {
        val variableId = insertTextVariable(insertVariable(type = VariableType.Text))

        val append1Result =
            store.updateValues(
                listOf(AppendValueOperation(NewTextValue(newValueProps(variableId), "1")))
            )

        store.updateValues(
            listOf(DeleteValueOperation(inserted.projectId, append1Result.first().id))
        )
        val round1Values = store.listValues(inserted.documentId)

        store.updateValues(
            listOf(DeleteValueOperation(inserted.projectId, append1Result.first().id))
        )
        val round2Values = store.listValues(inserted.documentId)

        assertEquals(
            round1Values,
            round2Values,
            "Should not have made any additional changes to values",
        )
      }

      @Test
      fun `can delete a table row whose values are all deleted`() {
        val tableVariableId = insertTableVariable()
        val columnVariableId = insertTextVariable()
        insertTableColumn(tableVariableId, columnVariableId)

        val row0Id = insertValue(variableId = tableVariableId, listPosition = 0)
        val row0OriginalValueId = insertValue(variableId = columnVariableId, textValue = "A")
        val row0DeletedValueId = insertValue(variableId = columnVariableId, isDeleted = true)
        insertValueTableRow(row0OriginalValueId, row0Id)
        insertValueTableRow(row0DeletedValueId, row0Id)

        store.updateValues(listOf(DeleteValueOperation(inserted.projectId, row0Id)))

        val updatedValues =
            store.listValues(
                projectId = inserted.projectId,
                variableIds = listOf(tableVariableId, columnVariableId),
                includeDeletedValues = false,
            )
        assertEquals(emptyList<ExistingValue>(), updatedValues)
      }
    }

    @Nested
    inner class PublishEvent {
      @BeforeEach
      fun setup() {
        insertModule()
        insertDeliverable()
      }

      @Test
      fun `publishes event if a variable value is updated`() {
        val variableId =
            insertVariableManifestEntry(insertTextVariable(deliverableId = inserted.deliverableId))

        val updatedValues =
            store.updateValues(
                listOf(AppendValueOperation(NewTextValue(newValueProps(variableId), "new")))
            )

        updatedValues.forEach { value ->
          eventPublisher.assertEventPublished(
              VariableValueUpdatedEvent(inserted.projectId, value.variableId)
          )
        }
      }

      @Test
      fun `throws exception if not permission to set triggerWorkflows to false`() {
        val variableId =
            insertVariableManifestEntry(insertTextVariable(deliverableId = inserted.deliverableId))

        every { user.canUpdateInternalVariableWorkflowDetails(any()) } returns false
        every { user.canReadProject(any()) } returns true
        assertThrows<AccessDeniedException> {
          store.updateValues(
              listOf(AppendValueOperation(NewTextValue(newValueProps(variableId), "new"))),
              triggerWorkflows = false,
          )
        }
      }

      @Test
      fun `publishes event if a variable value is written`() {
        val variableId = insertVariable(type = VariableType.Image)
        val fileId = insertFile()
        store.writeValue(NewImageValue(newValueProps(variableId), ImageValueDetails("", fileId)))
        eventPublisher.assertEventPublished(
            VariableValueUpdatedEvent(inserted.projectId, variableId)
        )
      }

      @Test
      fun `publishes events if a variable referenced in a child of a completed section is written`() {
        insertDocumentTemplate()
        insertVariableManifest()
        val documentId = insertDocument()
        val topSectionVariableId = insertVariableManifestEntry(insertSectionVariable())
        val middleSectionVariableId =
            insertVariableManifestEntry(insertSectionVariable(parentId = topSectionVariableId))
        val childSectionVariableId =
            insertVariableManifestEntry(insertSectionVariable(parentId = middleSectionVariableId))
        val variableId1 = insertTextVariable()
        val variableId2 = insertTextVariable()

        insertSectionValue(childSectionVariableId, listPosition = 0, usedVariableId = variableId1)
        insertSectionValue(childSectionVariableId, listPosition = 1, usedVariableId = variableId2)
        insertVariableWorkflowHistory(
            variableId = topSectionVariableId,
            status = VariableWorkflowStatus.Complete,
        )
        insertVariableWorkflowHistory(
            variableId = middleSectionVariableId,
            status = VariableWorkflowStatus.Complete,
        )

        store.updateValues(
            listOf(
                AppendValueOperation(NewTextValue(newValueProps(variableId1), "new 1")),
                AppendValueOperation(NewTextValue(newValueProps(variableId2), "new 2")),
            )
        )

        eventPublisher.assertEventPublished(
            CompletedSectionVariableUpdatedEvent(
                documentId,
                inserted.projectId,
                childSectionVariableId,
                middleSectionVariableId,
            ),
            "Event for middle section",
        )
        eventPublisher.assertEventPublished(
            CompletedSectionVariableUpdatedEvent(
                documentId,
                inserted.projectId,
                childSectionVariableId,
                topSectionVariableId,
            ),
            "Event for top section",
        )
      }

      @Test
      fun `only publishes events for completed parent sections when variable referenced in a child is written`() {
        insertDocumentTemplate()
        insertVariableManifest()
        val documentId = insertDocument()
        val topSectionVariableId = insertVariableManifestEntry(insertSectionVariable())
        val middleSectionVariableId =
            insertVariableManifestEntry(insertSectionVariable(parentId = topSectionVariableId))
        val childSectionVariableId =
            insertVariableManifestEntry(insertSectionVariable(parentId = middleSectionVariableId))
        val variableId = insertTextVariable()

        insertSectionValue(childSectionVariableId, listPosition = 0, usedVariableId = variableId)
        insertVariableWorkflowHistory(
            variableId = topSectionVariableId,
            status = VariableWorkflowStatus.Complete,
        )

        store.updateValues(
            listOf(AppendValueOperation(NewTextValue(newValueProps(variableId), "new 1")))
        )

        eventPublisher.assertEventNotPublished(
            CompletedSectionVariableUpdatedEvent(
                documentId,
                inserted.projectId,
                childSectionVariableId,
                middleSectionVariableId,
            ),
            "Event for middle section should not have been published",
        )
        eventPublisher.assertEventPublished(
            CompletedSectionVariableUpdatedEvent(
                documentId,
                inserted.projectId,
                childSectionVariableId,
                topSectionVariableId,
            ),
            "Event for top section",
        )
      }

      @Test
      fun `does not publish event if a variable previously referenced by a completed section is written`() {
        insertDocumentTemplate()
        insertVariableManifest()
        insertDocument()
        val sectionVariableId = insertVariableManifestEntry(insertSectionVariable())
        val variableId1 = insertTextVariable()
        val variableId2 = insertTextVariable()

        insertSectionValue(sectionVariableId, listPosition = 0, usedVariableId = variableId1)
        insertSectionValue(sectionVariableId, listPosition = 0, usedVariableId = variableId2)
        insertVariableWorkflowHistory(
            variableId = sectionVariableId,
            status = VariableWorkflowStatus.Complete,
        )

        store.updateValues(
            listOf(AppendValueOperation(NewTextValue(newValueProps(variableId1), "new")))
        )

        eventPublisher.assertEventNotPublished<CompletedSectionVariableUpdatedEvent>()
      }

      @Test
      fun `does not publish event if a variable referenced by a child of a previously-completed section is written`() {
        insertDocumentTemplate()
        insertVariableManifest()
        insertDocument()
        val parentSectionVariableId = insertVariableManifestEntry(insertSectionVariable())
        val childSectionVariableId =
            insertVariableManifestEntry(insertSectionVariable(parentId = parentSectionVariableId))
        val variableId = insertTextVariable()

        insertSectionValue(childSectionVariableId, listPosition = 0, usedVariableId = variableId)
        insertVariableWorkflowHistory(
            variableId = parentSectionVariableId,
            status = VariableWorkflowStatus.Complete,
        )
        insertVariableWorkflowHistory(
            variableId = parentSectionVariableId,
            status = VariableWorkflowStatus.InReview,
        )

        store.updateValues(
            listOf(AppendValueOperation(NewTextValue(newValueProps(variableId), "new 1")))
        )

        eventPublisher.assertEventNotPublished<CompletedSectionVariableUpdatedEvent>()
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
              insertTextVariable(insertVariable(type = VariableType.Text, isList = true))
          )

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
          variableId = variableId,
          listPosition = 1,
          textValue = "deleted",
          isDeleted = true,
      )

      assertEquals(1, store.fetchNextListPosition(inserted.projectId, variableId))
    }
  }

  @Nested
  inner class UpgradeSectionValueVariables {
    @Test
    fun `updates variable references in sections to use new variable IDs`() {
      val projectId = inserted.projectId
      insertDocumentTemplate()
      insertVariableManifest()
      insertDocument()

      val oldVariableId = insertTextVariable()
      insertValue(textValue = "referenced text", variableId = oldVariableId)
      val sectionVariableId = insertVariableManifestEntry(insertSectionVariable())

      insertSectionValue(listPosition = 0, textValue = "some text", variableId = sectionVariableId)
      val oldReferringSectionValueId =
          insertSectionValue(
              displayStyle = VariableInjectionDisplayStyle.Block,
              listPosition = 1,
              usageType = VariableUsageType.Injection,
              usedVariableId = oldVariableId,
              variableId = sectionVariableId,
          )

      val newVariableId =
          insertTextVariable(
              insertVariable(replacesVariableId = oldVariableId, type = VariableType.Text)
          )

      every { user.canUpdateInternalVariableWorkflowDetails(projectId) } returns true

      val oldValues =
          store.listValues(projectId = projectId, variableIds = listOf(sectionVariableId))
      val oldTextSectionValue = oldValues.single { it.listPosition == 0 }

      store.upgradeSectionValueVariables(mapOf(oldVariableId to newVariableId))

      val newValues =
          store.listValues(projectId = projectId, variableIds = listOf(sectionVariableId))
      val newTextSectionValue = newValues.single { it.listPosition == 0 }
      val newReferringSectionValue = newValues.single { it.listPosition == 1 }

      assertEquals(
          oldTextSectionValue,
          newTextSectionValue,
          "Text value should not have been modified",
      )
      assertNotEquals(
          oldReferringSectionValueId,
          newReferringSectionValue.id,
          "Value with variable reference should have been replaced",
      )
      assertEquals(
          SectionValueVariable(
              newVariableId,
              VariableUsageType.Injection,
              VariableInjectionDisplayStyle.Block,
          ),
          newReferringSectionValue.value,
          "Section should refer to new variable",
      )
    }

    @Test
    fun `does not modify sections with up-to-date variable references`() {
      val projectId = inserted.projectId
      insertDocumentTemplate()
      insertVariableManifest()
      insertDocument()
      val sectionVariableId = insertVariableManifestEntry(insertSectionVariable())

      val oldVariableId = insertTextVariable()

      // The first version of the section references the outdated variable, there is no project
      // value for this variable yet
      insertSectionValue(listPosition = 0, textValue = "some text", variableId = sectionVariableId)
      insertSectionValue(
          displayStyle = VariableInjectionDisplayStyle.Block,
          listPosition = 1,
          usageType = VariableUsageType.Injection,
          usedVariableId = oldVariableId,
          variableId = sectionVariableId,
      )

      // The variable is upgraded at some point
      val newVariableId =
          insertTextVariable(
              insertVariable(replacesVariableId = oldVariableId, type = VariableType.Text)
          )

      // The section has already been updated to use the new variable ID
      insertSectionValue(
          listPosition = 0,
          textValue = "some updated text",
          variableId = sectionVariableId,
      )
      insertSectionValue(
          displayStyle = VariableInjectionDisplayStyle.Block,
          listPosition = 1,
          usageType = VariableUsageType.Injection,
          usedVariableId = newVariableId,
          variableId = sectionVariableId,
      )

      // A project value is added to the new variable
      insertValue(textValue = "referenced text", variableId = newVariableId)

      every { user.canUpdateInternalVariableWorkflowDetails(projectId) } returns true

      val oldValues =
          store.listValues(projectId = projectId, variableIds = listOf(sectionVariableId))
      val oldReferringSectionValue = oldValues.single { it.listPosition == 1 }

      store.upgradeSectionValueVariables(mapOf(oldVariableId to newVariableId))

      val newValues =
          store.listValues(projectId = projectId, variableIds = listOf(sectionVariableId))
      val newReferringSectionValue = newValues.single { it.listPosition == 1 }

      assertEquals(
          oldReferringSectionValue.id,
          newReferringSectionValue.id,
          "Value with variable reference should not have been replaced",
      )
    }

    @Test
    fun `does not throw an error if there are updates to multiple projects with values for an updated variable`() {
      val projectId = inserted.projectId
      insertDocumentTemplate()
      insertVariableManifest()
      insertDocument(projectId = projectId)

      val oldVariableId = insertTextVariable()
      insertValue(textValue = "referenced text", variableId = oldVariableId)
      val sectionVariableId = insertVariableManifestEntry(insertSectionVariable())

      insertSectionValue(
          displayStyle = VariableInjectionDisplayStyle.Block,
          listPosition = 1,
          usageType = VariableUsageType.Injection,
          usedVariableId = oldVariableId,
          variableId = sectionVariableId,
      )

      val newVariableId =
          insertTextVariable(
              insertVariable(replacesVariableId = oldVariableId, type = VariableType.Text)
          )

      val otherProjectId = insertProject()
      insertValue(textValue = "other project referenced text", variableId = oldVariableId)
      insertSectionValue(
          displayStyle = VariableInjectionDisplayStyle.Block,
          listPosition = 1,
          usageType = VariableUsageType.Injection,
          usedVariableId = oldVariableId,
          variableId = sectionVariableId,
      )

      every { user.canUpdateInternalVariableWorkflowDetails(projectId) } returns true
      every { user.canUpdateInternalVariableWorkflowDetails(otherProjectId) } returns true

      assertDoesNotThrow {
        store.upgradeSectionValueVariables(mapOf(oldVariableId to newVariableId))
      }
    }
  }

  private fun existingValueProps(
      valueId: VariableValueId,
      variableId: VariableId,
      projectId: ProjectId = inserted.projectId,
      position: Int = 0,
      rowValueId: VariableValueId? = null,
      citation: String? = null,
  ): BaseVariableValueProperties<VariableValueId> {
    return BaseVariableValueProperties(
        citation = citation,
        id = valueId,
        listPosition = position,
        projectId = projectId,
        variableId = variableId,
        rowValueId = rowValueId,
    )
  }

  private fun newValueProps(
      variableId: VariableId,
      projectId: ProjectId = inserted.projectId,
      position: Int = 0,
      rowValueId: VariableValueId? = null,
      citation: String? = null,
  ): BaseVariableValueProperties<Nothing?> {
    return BaseVariableValueProperties(
        citation = citation,
        id = null,
        listPosition = position,
        projectId = projectId,
        variableId = variableId,
        rowValueId = rowValueId,
    )
  }
}
