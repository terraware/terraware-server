package com.terraformation.backend.nursery.db.batchStore

import com.terraformation.backend.db.ProjectInDifferentOrganizationException
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SubLocationId
import com.terraformation.backend.db.nursery.BatchId
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
            subLocationId = subLocationId,
        ),
        id = batchId,
        readyQuantity = 1,
        speciesId = speciesId)

    clock.instant = updateTime
  }

  @Test
  fun `updates values`() {
    val newProjectId = insertProject()
    val newSubLocationId = insertSubLocation()
    val before = batchesDao.fetchOneById(batchId)!!

    store.updateDetails(batchId, 1) {
      it.copy(notes = "new notes", projectId = newProjectId, readyByDate = LocalDate.of(2022, 1, 1),
        subLocationId = newSubLocationId)
    }

    val after = batchesDao.fetchOneById(batchId)!!

    assertEquals(
        before.copy(
            notes = "new notes",
            modifiedTime = updateTime,
            projectId = newProjectId,
            readyByDate = LocalDate.of(2022, 1, 1),
            subLocationId = newSubLocationId,
            version = 2),
        after)
  }

  @Test
  fun `can set optional values to null`() {
    val before = batchesDao.fetchOneById(batchId)!!

    store.updateDetails(batchId, 1) { it.copy(
        notes = null,
        projectId = null,
        readyByDate = null,
        subLocationId = null) }

    val after = batchesDao.fetchOneById(batchId)!!

    assertEquals(
        before.copy(
            notes = null,
            modifiedTime = updateTime,
            projectId = null,
            readyByDate = null,
            subLocationId = null,
            version = 2),
        after)
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
