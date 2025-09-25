package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.event.DeliverablesUploadedEvent
import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.tables.pojos.DeliverableVariablesRow
import com.terraformation.backend.db.accelerator.tables.references.DELIVERABLES
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.importer.CsvImportFailedException
import com.terraformation.backend.mockUser
import io.mockk.every
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DeliverablesImporterTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()
  private val eventPublisher = TestEventPublisher()

  private val importer: DeliverablesImporter by lazy {
    DeliverablesImporter(
        TestClock(),
        dslContext,
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
        ),
        eventPublisher,
    )
  }

  private val stableIdSuffix = "${UUID.randomUUID()}"

  private val header =
      "Name,ID,Description,Template,Module ID,Category,Sensitive?,Required?,Deliverable Type,Variable IDs"

  @BeforeEach
  fun setUp() {
    every { user.canManageDeliverables() } returns true
  }

  @Nested
  inner class ImportDeliverables {
    @Test
    fun `associates variables with deliverables`() {
      val moduleId = insertModule()
      val deliverableId1 = getUnusedDeliverableId()
      val deliverableId2 = getUnusedDeliverableId()
      val stableId1 = "1-$stableIdSuffix"
      val stableId2 = "2-$stableIdSuffix"
      val stableId3 = "3-$stableIdSuffix"
      val variableId1 = insertTextVariable(stableId = stableId1)
      val variableId2 = insertTextVariable(stableId = stableId2)
      val variableId3 = insertTextVariable(stableId = stableId3)

      val csv =
          header +
              "\nDeliverable 1,$deliverableId1,Description,,$moduleId,Compliance,no,no,Questions,\"$stableId1\n$stableId2\"" +
              "\nDeliverable 2,$deliverableId2,Description,,$moduleId,Compliance,no,no,Questions,\"$stableId2\n$stableId1\n$stableId3\""

      importer.importDeliverables(csv.byteInputStream())

      assertSetEquals(
          setOf(
              DeliverableVariablesRow(deliverableId1, variableId1, 0),
              DeliverableVariablesRow(deliverableId1, variableId2, 1),
              DeliverableVariablesRow(deliverableId2, variableId2, 0),
              DeliverableVariablesRow(deliverableId2, variableId1, 1),
              DeliverableVariablesRow(deliverableId2, variableId3, 2),
          ),
          deliverableVariablesDao.findAll().toSet(),
      )

      eventPublisher.assertEventPublished { event -> event is DeliverablesUploadedEvent }
    }

    @Test
    fun `automatically includes column variables if table is associated with deliverable`() {
      val moduleId = insertModule()
      val deliverableId = getUnusedDeliverableId()
      val tableStableId = "1-$stableIdSuffix"
      val columnStableId1 = "2-$stableIdSuffix"
      val columnStableId2 = "3-$stableIdSuffix"
      val tableVariableId = insertTableVariable(stableId = tableStableId)
      val columnVariableId1 = insertTextVariable(stableId = columnStableId1)
      val columnVariableId2 = insertTextVariable(stableId = columnStableId2)
      insertTableColumn(tableVariableId, columnVariableId1, position = 0)
      insertTableColumn(tableVariableId, columnVariableId2, position = 1)

      // One of the columns is explicitly listed in the variables list, but not the other.
      val csv =
          header +
              "\nDeliverable 1,$deliverableId,Description,,$moduleId,Compliance,no,no,Questions,\"$tableStableId\n$columnStableId2\""

      importer.importDeliverables(csv.byteInputStream())

      assertSetEquals(
          setOf(
              DeliverableVariablesRow(deliverableId, tableVariableId, 0),
              DeliverableVariablesRow(deliverableId, columnVariableId1, 1),
              DeliverableVariablesRow(deliverableId, columnVariableId2, 2),
          ),
          deliverableVariablesDao.findAll().toSet(),
      )
    }

    @Test
    fun `uses latest variable versions`() {
      val moduleId = insertModule()
      val deliverableId = getUnusedDeliverableId()
      val stableId = "1-$stableIdSuffix"
      val oldVariableId =
          insertTextVariable(insertVariable(type = VariableType.Text, stableId = stableId))
      val newVariableId =
          insertTextVariable(
              insertVariable(
                  type = VariableType.Text,
                  stableId = stableId,
                  replacesVariableId = oldVariableId,
              )
          )

      val csv =
          header +
              "\nDeliverable 1,$deliverableId,Description,,$moduleId,Compliance,no,no,Questions,$stableId"

      importer.importDeliverables(csv.byteInputStream())

      assertEquals(
          listOf(DeliverableVariablesRow(deliverableId, newVariableId, 0)),
          deliverableVariablesDao.findAll(),
      )
    }

    @Test
    fun `updates variable positions if list is updated`() {
      val moduleId = insertModule()
      val deliverableId = insertDeliverable()
      val stableId1 = "1-$stableIdSuffix"
      val stableId2 = "2-$stableIdSuffix"
      val stableId3 = "3-$stableIdSuffix"
      val variableId1 = insertTextVariable(stableId = stableId1)
      insertDeliverableVariable(position = 0)
      insertTextVariable(stableId = stableId2)
      insertDeliverableVariable(position = 1)
      val variableId3 = insertTextVariable(stableId = stableId3)
      insertDeliverableVariable(position = 2)

      val csv =
          header +
              "\nDeliverable 1,$deliverableId,Description,,$moduleId,Compliance,no,no,Questions,\"$stableId3\n$stableId1\""

      importer.importDeliverables(csv.byteInputStream())

      assertSetEquals(
          setOf(
              DeliverableVariablesRow(deliverableId, variableId3, 0),
              DeliverableVariablesRow(deliverableId, variableId1, 1),
          ),
          deliverableVariablesDao.findAll().toSet(),
      )
    }

    @Test
    fun `rejects variable IDs for non-question deliverables`() {
      val moduleId = insertModule()
      val deliverableId = getUnusedDeliverableId()
      val stableId = "1-$stableIdSuffix"
      insertTextVariable(stableId = stableId)

      val csv =
          header +
              "\nDeliverable 1,$deliverableId,Description,,$moduleId,Compliance,no,no,Document,$stableId"

      assertThrows<CsvImportFailedException> { importer.importDeliverables(csv.byteInputStream()) }
    }
  }

  /**
   * Returns a deliverable ID that doesn't exist in the database and won't conflict with IDs from
   * other tests running in parallel.
   */
  private fun getUnusedDeliverableId(): DeliverableId {
    val deliverableId = insertDeliverable()
    dslContext.deleteFrom(DELIVERABLES).where(DELIVERABLES.ID.eq(deliverableId)).execute()
    return deliverableId
  }
}
