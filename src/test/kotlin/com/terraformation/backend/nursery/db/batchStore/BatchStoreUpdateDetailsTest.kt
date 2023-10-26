package com.terraformation.backend.nursery.db.batchStore

import com.terraformation.backend.db.ProjectInDifferentOrganizationException
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SeedTreatment
import com.terraformation.backend.db.default_schema.SubLocationId
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.BatchSubstrate
import com.terraformation.backend.db.nursery.tables.pojos.BatchesRow
import com.terraformation.backend.nursery.db.BatchStaleException
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class BatchStoreUpdateDetailsTest : BatchStoreTest() {
  private val batchId = BatchId(1)
  private val projectId: ProjectId by lazy { insertProject() }
  private val subLocationId: SubLocationId by lazy { insertSubLocation(facilityId = facilityId) }
  private val updateTime = Instant.ofEpochSecond(1000)

  @BeforeEach
  fun setUpTestBatch() {
    insertBatch(
        BatchesRow(
            notes = "initial notes",
            projectId = projectId,
            readyByDate = LocalDate.EPOCH,
            substrateId = BatchSubstrate.Other,
            substrateNotes = "My substrate",
            treatmentId = SeedTreatment.Light,
        ),
        id = batchId,
        readyQuantity = 1,
        speciesId = speciesId)
    insertBatchSubLocation(subLocationId = subLocationId)

    clock.instant = updateTime
  }

  @Test
  fun `updates values`() {
    val newProjectId = insertProject()
    val newSubLocationId1 = insertSubLocation()
    val newSubLocationId2 = insertSubLocation()
    val before = batchesDao.fetchOneById(batchId)!!

    insertBatchSubLocation(subLocationId = newSubLocationId1)

    store.updateDetails(batchId, 1) {
      it.copy(
          notes = "new notes",
          projectId = newProjectId,
          readyByDate = LocalDate.of(2022, 1, 1),
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
            notes = "new notes",
            modifiedTime = updateTime,
            projectId = newProjectId,
            readyByDate = LocalDate.of(2022, 1, 1),
            substrateId = BatchSubstrate.Moss,
            substrateNotes = "New substrate notes",
            treatmentId = SeedTreatment.Light,
            treatmentNotes = "Treatment notes",
            version = 2,
        ),
        after)

    assertEquals(
        setOf(newSubLocationId1, newSubLocationId2),
        batchSubLocationsDao.findAll().map { it.subLocationId }.toSet(),
        "Should have replaced sub-locations list")
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
            version = 2),
        after)
    assertEquals(
        emptyList<Any>(), batchSubLocationsDao.findAll(), "Should have removed sub-locations")
  }

  @Test
  fun `throws exception if version number does not match current version`() {
    assertThrows<BatchStaleException> { store.updateDetails(batchId = batchId, version = 0) { it } }
  }

  @Test
  fun `throws exception if project is not in same organization as nursery`() {
    val otherOrganizationId = OrganizationId(2)
    insertOrganization(otherOrganizationId)
    val otherOrgProjectId = insertProject(organizationId = otherOrganizationId)

    assertThrows<ProjectInDifferentOrganizationException> {
      store.updateDetails(batchId = batchId, version = 1) { it.copy(projectId = otherOrgProjectId) }
    }
  }
}
