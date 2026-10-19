package com.terraformation.backend.tracking.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.ProjectInDifferentOrganizationException
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.tracking.DraftPlantingSiteId
import com.terraformation.backend.db.tracking.DraftPlantingSiteType
import com.terraformation.backend.db.tracking.DraftStratumId
import com.terraformation.backend.db.tracking.DraftSubstratumId
import com.terraformation.backend.db.tracking.tables.records.DraftPlantingSitesRecord
import com.terraformation.backend.mockUser
import com.terraformation.backend.rectangle
import com.terraformation.backend.tracking.model.DraftPlantingSiteMap
import com.terraformation.backend.tracking.model.DraftPlantingSiteModel
import com.terraformation.backend.tracking.model.DraftStratumModel
import com.terraformation.backend.tracking.model.DraftSubstratumModel
import io.mockk.every
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import org.jooq.JSONB
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.locationtech.jts.geom.MultiPolygon
import org.springframework.security.access.AccessDeniedException

class DraftPlantingSiteStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val store: DraftPlantingSiteStore by lazy {
    DraftPlantingSiteStore(clock, dslContext, ParentStore(dslContext))
  }

  private val sampleData = JSONB.valueOf("{\"foo\":\"bar\"}")
  private val timeZone = ZoneId.of("Pacific/Honolulu")

  private lateinit var organizationId: OrganizationId

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()

    every { user.canCreateDraftPlantingSite(any()) } returns true
    every { user.canDeleteDraftPlantingSite(any()) } returns true
    every { user.canReadDraftPlantingSite(any()) } returns true
    every { user.canReadOrganization(any()) } returns true
    every { user.canReadProject(any()) } returns true
    every { user.canUpdateDraftPlantingSite(any()) } returns true
  }

  private fun substratumModel(
      name: String,
      id: DraftSubstratumId? = null,
      boundary: MultiPolygon = rectangle(50),
  ) = DraftSubstratumModel(id = id, name = name, boundary = boundary)

  private fun stratumModel(
      name: String,
      id: DraftStratumId? = null,
      boundary: MultiPolygon = rectangle(100),
      initialPlantingDensity: BigDecimal = BigDecimal(1500),
      substrata: List<DraftSubstratumModel> = emptyList(),
  ) =
      DraftStratumModel(
          id = id,
          name = name,
          boundary = boundary,
          initialPlantingDensity = initialPlantingDensity,
          substrata = substrata,
      )

  private fun siteModel(
      siteType: DraftPlantingSiteType = DraftPlantingSiteType.Detailed,
      strata: List<DraftStratumModel> = emptyList(),
      name: String = "Name",
      projectId: ProjectId? = null,
      organizationId: OrganizationId = this.organizationId,
  ) =
      DraftPlantingSiteModel(
          id = null,
          organizationId = organizationId,
          projectId = projectId,
          name = name,
          description = "Description",
          timeZone = timeZone,
          map =
              DraftPlantingSiteMap(
                  siteType = siteType,
                  siteEditStep = "step",
                  boundarySource = "source",
                  boundary = rectangle(1000),
                  exclusion = rectangle(10),
                  strata = strata,
              ),
      )

  @Nested
  inner class FetchOneById {
    @Test
    fun `returns record`() {
      insertDraftPlantingSite(name = "Fetch test")

      val record = store.fetchOneById(inserted.draftPlantingSiteId)

      assertEquals("Fetch test", record.name, "Name")
    }

    @Test
    fun `throws exception if no permission to read draft`() {
      every { user.canReadDraftPlantingSite(any()) } returns false

      insertDraftPlantingSite()

      assertThrows<DraftPlantingSiteNotFoundException> {
        store.fetchOneById(inserted.draftPlantingSiteId)
      }
    }
  }

  @Nested
  inner class Create {
    @Test
    fun `creates draft`() {
      insertProject()

      val expected =
          DraftPlantingSitesRecord(
              createdBy = user.userId,
              createdTime = clock.instant,
              data = sampleData,
              description = "Description",
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
              name = "Name",
              numSubstrata = 2,
              numStrata = 1,
              organizationId = organizationId,
              projectId = inserted.projectId,
              timeZone = timeZone,
          )

      val actual =
          store.create(
              data = sampleData,
              name = "Name",
              organizationId = organizationId,
              description = "Description",
              numSubstrata = 2,
              numStrata = 1,
              projectId = inserted.projectId,
              timeZone = timeZone,
          )

      assertEquals(expected, actual.copy())
    }

    @Test
    fun `throws exception if no permission to create drafts`() {
      every { user.canCreateDraftPlantingSite(any()) } returns false

      assertThrows<AccessDeniedException> {
        store.create(JSONB.valueOf("{}"), "Name", organizationId)
      }
    }

    @Test
    fun `throws exception if project is in wrong organization`() {
      insertOrganization()
      insertProject()

      assertThrows<ProjectInDifferentOrganizationException> {
        store.create(JSONB.valueOf("{}"), "Name", organizationId, projectId = inserted.projectId)
      }
    }
  }

  @Nested
  inner class Delete {
    @Test
    fun `deletes correct draft`() {
      val otherDraftId1 = insertDraftPlantingSite()
      val draftIdToDelete = insertDraftPlantingSite()
      val otherDraftId2 = insertDraftPlantingSite()

      store.delete(draftIdToDelete)

      assertSetEquals(
          setOf(otherDraftId1, otherDraftId2),
          draftPlantingSitesDao.findAll().map { it.id }.toSet(),
          "Remaining IDs after deletion",
      )
    }

    @Test
    fun `throws exception if no permission to delete drafts`() {
      every { user.canDeleteDraftPlantingSite(any()) } returns false

      insertDraftPlantingSite()

      assertThrows<AccessDeniedException> { store.delete(inserted.draftPlantingSiteId) }
    }
  }

  @Nested
  inner class Update {
    @Test
    fun `updates draft`() {
      insertProject()
      insertDraftPlantingSite()

      clock.instant = Instant.EPOCH.plusSeconds(30)

      val expected =
          DraftPlantingSitesRecord(
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              data = sampleData,
              description = "Description",
              id = inserted.draftPlantingSiteId,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
              name = "New name",
              numSubstrata = 2,
              numStrata = 1,
              organizationId = organizationId,
              projectId = inserted.projectId,
              siteTypeId = DraftPlantingSiteType.Detailed,
              timeZone = timeZone,
          )

      store.update(inserted.draftPlantingSiteId) {
        it.data = sampleData
        it.description = "Description"
        it.name = "New name"
        it.numSubstrata = 2
        it.numStrata = 1
        it.projectId = inserted.projectId
        it.timeZone = timeZone
      }

      val actual = store.fetchOneById(inserted.draftPlantingSiteId)

      assertEquals(expected, actual)
    }

    @Test
    fun `throws exception if no permission to update drafts`() {
      every { user.canUpdateDraftPlantingSite(any()) } returns false

      insertDraftPlantingSite()

      assertThrows<AccessDeniedException> { store.update(inserted.draftPlantingSiteId) {} }
    }

    @Test
    fun `throws exception if callback attempts to change read-only field`() {
      insertDraftPlantingSite()
      val otherOrgId = insertOrganization()

      assertThrows<AccessDeniedException> {
        store.update(inserted.draftPlantingSiteId) { it.organizationId = otherOrgId }
      }
    }

    @Test
    fun `throws exception if project is in wrong organization`() {
      insertDraftPlantingSite()

      val otherOrgId = insertOrganization()
      insertProject(organizationId = otherOrgId)

      assertThrows<ProjectInDifferentOrganizationException> {
        store.update(inserted.draftPlantingSiteId) { it.projectId = inserted.projectId }
      }
    }
  }

  @Nested
  inner class FetchModelById {
    @Test
    fun `returns model`() {
      val projectId = insertProject()
      insertDraftPlantingSite(
          boundary = rectangle(500),
          boundarySource = "shapefile",
          description = "Description",
          exclusion = rectangle(10),
          name = "Fetch test",
          projectId = projectId,
          siteEditStep = "zone_boundaries",
          timeZone = timeZone,
      )

      val expected =
          DraftPlantingSiteModel(
              createdBy = user.userId,
              createdTime = clock.instant,
              description = "Description",
              id = inserted.draftPlantingSiteId,
              map =
                  DraftPlantingSiteMap(
                      boundary = rectangle(500),
                      boundarySource = "shapefile",
                      exclusion = rectangle(10),
                      siteEditStep = "zone_boundaries",
                      siteType = DraftPlantingSiteType.Detailed,
                      strata = emptyList(),
                  ),
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
              name = "Fetch test",
              organizationId = organizationId,
              projectId = projectId,
              timeZone = timeZone,
          )

      assertEquals(expected, store.fetchModelById(inserted.draftPlantingSiteId))
    }

    @Test
    fun `returns strata and substrata in id order`() {
      insertDraftPlantingSite()
      val stratumId1 = insertDraftStratum(name = "Zebra")
      val substratumId1 = insertDraftSubstratum(name = "Zulu")
      val substratumId2 = insertDraftSubstratum(name = "Alpha")
      val stratumId2 = insertDraftStratum(name = "Alpha")
      val substratumId3 = insertDraftSubstratum(name = "Beta")

      val expected =
          listOf(
              DraftStratumModel(
                  id = stratumId1,
                  name = "Zebra",
                  boundary = rectangle(100),
                  initialPlantingDensity = BigDecimal(1500),
                  substrata =
                      listOf(
                          DraftSubstratumModel(substratumId1, "Zulu", rectangle(100)),
                          DraftSubstratumModel(substratumId2, "Alpha", rectangle(100)),
                      ),
              ),
              DraftStratumModel(
                  id = stratumId2,
                  name = "Alpha",
                  boundary = rectangle(100),
                  initialPlantingDensity = BigDecimal(1500),
                  substrata = listOf(DraftSubstratumModel(substratumId3, "Beta", rectangle(100))),
              ),
          )

      assertEquals(expected, store.fetchModelById(inserted.draftPlantingSiteId).map.strata)
    }

    @Test
    fun `throws exception if no permission to read draft`() {
      every { user.canReadDraftPlantingSite(any()) } returns false

      insertDraftPlantingSite()

      assertThrows<DraftPlantingSiteNotFoundException> {
        store.fetchModelById(inserted.draftPlantingSiteId)
      }
    }
  }

  @Nested
  inner class CreateModel {
    @Test
    fun `stores map columns and strata, computes counts`() {
      insertProject()
      clock.instant = Instant.ofEpochSecond(1234)

      val model =
          siteModel(
              projectId = inserted.projectId,
              strata =
                  listOf(
                      stratumModel(
                          "S1",
                          substrata = listOf(substratumModel("S1-A"), substratumModel("S1-B")),
                      ),
                      stratumModel(
                          "S2",
                          initialPlantingDensity = BigDecimal(2500),
                          substrata = listOf(substratumModel("S2-A")),
                      ),
                  ),
          )

      val created = store.createModel(model)

      val row = draftPlantingSitesDao.fetchOneById(created.id!!)!!
      assertEquals(DraftPlantingSiteType.Detailed, row.siteTypeId, "Site type")
      assertEquals("step", row.siteEditStep, "Site edit step")
      assertEquals("source", row.boundarySource, "Boundary source")
      assertEquals("Description", row.description, "Description")
      assertEquals("Name", row.name, "Name")
      assertEquals(timeZone, row.timeZone, "Time zone")
      assertEquals(inserted.projectId, row.projectId, "Project ID")
      assertEquals(organizationId, row.organizationId, "Organization ID")
      assertEquals(user.userId, row.createdBy, "Created by")
      assertEquals(user.userId, row.modifiedBy, "Modified by")
      assertEquals(clock.instant, row.createdTime, "Created time")
      assertEquals(clock.instant, row.modifiedTime, "Modified time")
      assertEquals(model.map.boundary, row.boundary, "Boundary")
      assertEquals(model.map.exclusion, row.exclusion, "Exclusion")

      val strata = draftStrataDao.findAll().sortedBy { it.id }
      val substrata = draftSubstrataDao.findAll()
      assertEquals(2, strata.size, "Number of stored strata")
      assertEquals(3, substrata.size, "Number of stored substrata")
      assertEquals(listOf("S1", "S2"), strata.map { it.name }, "Stratum names")
      assertEquals(
          BigDecimal(2500).compareTo(strata[1].initialPlantingDensity),
          0,
          "Initial planting density",
      )
      assertSetEquals(
          setOf("S1-A", "S1-B", "S2-A"),
          substrata.map { it.name }.toSet(),
          "Substratum names",
      )

      assertEquals(
          model.copy(
              id = created.id,
              createdBy = user.userId,
              createdTime = clock.instant,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
              map =
                  model.map.copy(
                      strata =
                          created.map.strata.mapIndexed { index, stratum ->
                            model.map.strata[index].copy(
                                id = stratum.id,
                                substrata =
                                    stratum.substrata.mapIndexed { subIndex, substratum ->
                                      model.map.strata[index]
                                          .substrata[subIndex]
                                          .copy(id = substratum.id)
                                    },
                            )
                          }
                  ),
          ),
          created,
          "Returned model",
      )
      assertEquals(
          created,
          store.fetchModelById(created.id),
          "Returned model should match stored model",
      )
    }

    @Test
    fun `ignores id and audit fields in model`() {
      val otherUserId = insertUser()
      clock.instant = Instant.ofEpochSecond(500)

      val created =
          store.createModel(
              siteModel()
                  .copy(
                      id = DraftPlantingSiteId(9999),
                      createdBy = otherUserId,
                      createdTime = Instant.EPOCH,
                      modifiedBy = otherUserId,
                      modifiedTime = Instant.EPOCH,
                  )
          )

      assertNotEquals(DraftPlantingSiteId(9999), created.id, "ID")
      assertEquals(user.userId, created.createdBy, "Created by")
      assertEquals(clock.instant, created.createdTime, "Created time")
      assertEquals(user.userId, created.modifiedBy, "Modified by")
      assertEquals(clock.instant, created.modifiedTime, "Modified time")
    }

    @Test
    fun `creates simple site with no strata`() {
      val created = store.createModel(siteModel(siteType = DraftPlantingSiteType.Simple))

      val row = draftPlantingSitesDao.fetchOneById(created.id!!)!!
      assertEquals(DraftPlantingSiteType.Simple, row.siteTypeId, "Site type")
      assertEquals(0, draftStrataDao.count(), "Number of strata")
    }

    @Test
    fun `rejects strata on simple site`() {
      assertThrows<IllegalArgumentException> {
        store.createModel(
            siteModel(
                siteType = DraftPlantingSiteType.Simple,
                strata = listOf(stratumModel("S1")),
            )
        )
      }

      assertEquals(emptyList<Any>(), draftPlantingSitesDao.findAll(), "Stored drafts")
      assertEquals(emptyList<Any>(), draftStrataDao.findAll(), "Stored strata")
    }

    @Test
    fun `rejects duplicate stratum names`() {
      assertThrows<IllegalArgumentException> {
        store.createModel(siteModel(strata = listOf(stratumModel("Dup"), stratumModel("Dup"))))
      }

      assertEquals(emptyList<Any>(), draftPlantingSitesDao.findAll(), "Stored drafts")
      assertEquals(emptyList<Any>(), draftStrataDao.findAll(), "Stored strata")
    }

    @Test
    fun `rejects client-supplied stratum and substratum ids`() {
      val draftId = insertDraftPlantingSite()
      val stratumId = insertDraftStratum(name = "Existing")
      val substratumId = insertDraftSubstratum(name = "Existing")

      assertThrows<DraftStratumNotFoundException> {
        store.createModel(siteModel(strata = listOf(stratumModel("S1", id = stratumId))))
      }
      assertThrows<DraftSubstratumNotFoundException> {
        store.createModel(
            siteModel(
                strata =
                    listOf(
                        stratumModel("S1", substrata = listOf(substratumModel("A", substratumId)))
                    )
            )
        )
      }

      assertEquals(listOf(draftId), draftPlantingSitesDao.findAll().map { it.id }, "Stored drafts")
      assertEquals(listOf(stratumId), draftStrataDao.findAll().map { it.id }, "Stored strata")
      assertEquals(
          listOf(substratumId),
          draftSubstrataDao.findAll().map { it.id },
          "Stored substrata",
      )
    }

    @Test
    fun `rejects duplicate substratum names in a stratum`() {
      assertThrows<IllegalArgumentException> {
        store.createModel(
            siteModel(
                strata =
                    listOf(
                        stratumModel(
                            "S1",
                            substrata = listOf(substratumModel("Dup"), substratumModel("Dup")),
                        )
                    )
            )
        )
      }

      assertEquals(emptyList<Any>(), draftPlantingSitesDao.findAll(), "Stored drafts")
    }

    @Test
    fun `throws exception if no permission to create drafts`() {
      every { user.canCreateDraftPlantingSite(any()) } returns false

      assertThrows<AccessDeniedException> { store.createModel(siteModel()) }
    }

    @Test
    fun `throws exception if project is in wrong organization`() {
      insertOrganization()
      insertProject()

      assertThrows<ProjectInDifferentOrganizationException> {
        store.createModel(siteModel(projectId = inserted.projectId))
      }
    }
  }
}
