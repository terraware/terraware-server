package com.terraformation.backend.nursery.db.batchStore

import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.db.ProjectInDifferentOrganizationException
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SeedTreatment
import com.terraformation.backend.db.default_schema.SubLocationId
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.BatchSubstrate
import com.terraformation.backend.db.nursery.tables.pojos.BatchDetailsHistoryRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchDetailsHistorySubLocationsRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchesRow
import com.terraformation.backend.db.nursery.tables.references.BATCH_DETAILS_HISTORY
import com.terraformation.backend.db.nursery.tables.references.BATCH_SUB_LOCATIONS
import com.terraformation.backend.nursery.db.BatchStaleException
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class BatchStoreUpdateDetailsTest : BatchStoreTest() {
  private lateinit var batchId: BatchId
  private val projectId: ProjectId by lazy { insertProject() }
  private val subLocationId: SubLocationId by lazy { insertSubLocation(facilityId = facilityId) }
  private val updateTime = Instant.ofEpochSecond(1000)

  @BeforeEach
  fun setUpTestBatch() {
    batchId =
        insertBatch(
            BatchesRow(
                notes = "initial notes",
                projectId = projectId,
                readyByDate = LocalDate.EPOCH,
                substrateId = BatchSubstrate.Other,
                substrateNotes = "My substrate",
                treatmentId = SeedTreatment.Light,
            ),
            readyQuantity = 1,
            speciesId = speciesId,
        )
    insertBatchSubLocation(subLocationId = subLocationId)

    clock.instant = updateTime
  }

  @Test
  fun `updates values`() {
    val newProjectId = insertProject(name = "New Project")
    val newSubLocationId1 = insertSubLocation(name = "New Location 1")
    val newSubLocationId2 = insertSubLocation(name = "New Location 2")
    val before = batchesDao.fetchOneById(batchId)!!

    insertBatchSubLocation(subLocationId = newSubLocationId1)

    store.updateDetails(batchId, 1) {
      it.copy(
          germinationStartedDate = LocalDate.of(2021, 12, 2),
          notes = "new notes",
          projectId = newProjectId,
          readyByDate = LocalDate.of(2022, 1, 1),
          seedsSownDate = LocalDate.of(2022, 11, 18),
          subLocationIds = setOf(newSubLocationId1, newSubLocationId2),
          substrate = BatchSubstrate.Moss,
          substrateNotes = "New substrate notes",
          treatment = SeedTreatment.Light,
          treatmentNotes = "Treatment notes",
      )
    }

    val after = batchesDao.fetchOneById(batchId)!!

    assertEquals(
        before.copy(
            germinationStartedDate = LocalDate.of(2021, 12, 2),
            notes = "new notes",
            modifiedTime = updateTime,
            projectId = newProjectId,
            readyByDate = LocalDate.of(2022, 1, 1),
            seedsSownDate = LocalDate.of(2022, 11, 18),
            substrateId = BatchSubstrate.Moss,
            substrateNotes = "New substrate notes",
            treatmentId = SeedTreatment.Light,
            treatmentNotes = "Treatment notes",
            version = 2,
        ),
        after,
    )

    assertSetEquals(
        setOf(newSubLocationId1, newSubLocationId2),
        batchSubLocationsDao.findAll().map { it.subLocationId }.toSet(),
        "Should have replaced sub-locations list",
    )

    assertEquals(
        listOf(
            BatchDetailsHistoryRow(
                batchId = batchId,
                createdBy = user.userId,
                createdTime = updateTime,
                germinationStartedDate = LocalDate.of(2021, 12, 2),
                notes = "new notes",
                readyByDate = LocalDate.of(2022, 1, 1),
                projectId = newProjectId,
                projectName = "New Project",
                seedsSownDate = LocalDate.of(2022, 11, 18),
                substrateId = BatchSubstrate.Moss,
                substrateNotes = "New substrate notes",
                treatmentId = SeedTreatment.Light,
                treatmentNotes = "Treatment notes",
                version = 2,
            ),
        ),
        batchDetailsHistoryDao.findAll().map { it.copy(id = null) },
    )

    assertSetEquals(
        setOf(
            BatchDetailsHistorySubLocationsRow(
                subLocationId = newSubLocationId1,
                subLocationName = "New Location 1",
            ),
            BatchDetailsHistorySubLocationsRow(
                subLocationId = newSubLocationId2,
                subLocationName = "New Location 2",
            ),
        ),
        batchDetailsHistorySubLocationsDao
            .findAll()
            .map { it.copy(batchDetailsHistoryId = null) }
            .toSet(),
    )
  }

  @Test
  fun `does not insert details history entry if nothing was edited`() {
    store.updateDetails(batchId, 1) { it }

    assertTableEmpty(BATCH_DETAILS_HISTORY)
  }

  @Test
  fun `can set optional values to null`() {
    val before = batchesDao.fetchOneById(batchId)!!

    store.updateDetails(batchId, 1) {
      it.copy(notes = null, projectId = null, readyByDate = null, subLocationIds = emptySet())
    }

    val after = batchesDao.fetchOneById(batchId)!!

    assertEquals(
        before.copy(
            notes = null,
            modifiedTime = updateTime,
            projectId = null,
            readyByDate = null,
            version = 2,
        ),
        after,
    )
    assertTableEmpty(BATCH_SUB_LOCATIONS, "Should have removed sub-locations")
  }

  @Test
  fun `throws exception if version number does not match current version`() {
    assertThrows<BatchStaleException> { store.updateDetails(batchId = batchId, version = 0) { it } }
  }

  @Test
  fun `throws exception if project is not in same organization as nursery`() {
    insertOrganization()
    val otherOrgProjectId = insertProject()

    assertThrows<ProjectInDifferentOrganizationException> {
      store.updateDetails(batchId = batchId, version = 1) { it.copy(projectId = otherOrgProjectId) }
    }
  }
}
