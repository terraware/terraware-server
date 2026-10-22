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
import com.terraformation.backend.db.tracking.tables.records.DraftStrataRecord
import com.terraformation.backend.db.tracking.tables.records.DraftSubstrataRecord
import com.terraformation.backend.db.tracking.tables.references.DRAFT_PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.DRAFT_STRATA
import com.terraformation.backend.db.tracking.tables.references.DRAFT_SUBSTRATA
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

  /** Clears stratum and substratum IDs so drafts can be compared when new IDs aren't known. */
  private fun DraftPlantingSiteModel.withoutStratumIds() =
      copy(
          map =
              map.copy(
                  strata =
                      map.strata.map { stratum ->
                        stratum.copy(
                            id = null,
                            substrata = stratum.substrata.map { it.copy(id = null) },
                        )
                      }
              )
      )

  private fun DraftPlantingSiteModel.allStratumIds(): Set<Any> =
      map.strata
          .flatMap { stratum -> listOfNotNull(stratum.id) + stratum.substrata.mapNotNull { it.id } }
          .toSet()

  /** Inserts a draft with two strata, the first of which has two substrata. */
  private fun insertDraftWithStrata(): DraftPlantingSiteId {
    val draftId = insertDraftPlantingSite()
    insertDraftStratum(name = "S1")
    insertDraftSubstratum(name = "S1-A")
    insertDraftSubstratum(name = "S1-B")
    insertDraftStratum(name = "S2")
    insertDraftSubstratum(name = "S2-A")
    return draftId
  }

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

      val created = store.create(model)
      val draftId = created.id!!
      val stratumIds = draftStrataDao.findAll().associate { it.name!! to it.id!! }
      val substratumIds = draftSubstrataDao.findAll().associate { it.name!! to it.id!! }

      assertTableEquals(
          DraftPlantingSitesRecord(
              boundary = rectangle(1000),
              boundarySource = "source",
              createdBy = user.userId,
              createdTime = clock.instant,
              description = "Description",
              exclusion = rectangle(10),
              id = draftId,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
              name = "Name",
              organizationId = organizationId,
              projectId = inserted.projectId,
              siteEditStep = "step",
              siteTypeId = DraftPlantingSiteType.Detailed,
              timeZone = timeZone,
          )
      )
      assertTableEquals(
          listOf(
              DraftStrataRecord(
                  boundary = rectangle(100),
                  draftPlantingSiteId = draftId,
                  initialPlantingDensity = BigDecimal(1500),
                  name = "S1",
              ),
              DraftStrataRecord(
                  boundary = rectangle(100),
                  draftPlantingSiteId = draftId,
                  initialPlantingDensity = BigDecimal(2500),
                  name = "S2",
              ),
          )
      )
      assertTableEquals(
          listOf(
              DraftSubstrataRecord(
                  boundary = rectangle(50),
                  draftStratumId = stratumIds["S1"],
                  name = "S1-A",
              ),
              DraftSubstrataRecord(
                  boundary = rectangle(50),
                  draftStratumId = stratumIds["S1"],
                  name = "S1-B",
              ),
              DraftSubstrataRecord(
                  boundary = rectangle(50),
                  draftStratumId = stratumIds["S2"],
                  name = "S2-A",
              ),
          )
      )

      assertEquals(
          model.copy(
              id = draftId,
              createdBy = user.userId,
              createdTime = clock.instant,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
              map =
                  model.map.copy(
                      strata =
                          listOf(
                              stratumModel(
                                  "S1",
                                  id = stratumIds["S1"],
                                  substrata =
                                      listOf(
                                          substratumModel("S1-A", substratumIds["S1-A"]),
                                          substratumModel("S1-B", substratumIds["S1-B"]),
                                      ),
                              ),
                              stratumModel(
                                  "S2",
                                  id = stratumIds["S2"],
                                  initialPlantingDensity = BigDecimal(2500),
                                  substrata =
                                      listOf(substratumModel("S2-A", substratumIds["S2-A"])),
                              ),
                          )
                  ),
          ),
          created,
          "Returned model",
      )
    }

    @Test
    fun `ignores id and audit fields in model`() {
      val otherUserId = insertUser()
      clock.instant = Instant.ofEpochSecond(500)

      val created =
          store.create(
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
      assertEquals(
          siteModel()
              .copy(
                  id = created.id,
                  createdBy = user.userId,
                  createdTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              ),
          created,
      )
    }

    @Test
    fun `creates simple site with no strata`() {
      val created = store.create(siteModel(siteType = DraftPlantingSiteType.Simple))

      assertTableEquals(
          DraftPlantingSitesRecord(
              boundary = rectangle(1000),
              boundarySource = "source",
              createdBy = user.userId,
              createdTime = clock.instant,
              description = "Description",
              exclusion = rectangle(10),
              id = created.id,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
              name = "Name",
              organizationId = organizationId,
              siteEditStep = "step",
              siteTypeId = DraftPlantingSiteType.Simple,
              timeZone = timeZone,
          )
      )
      assertTableEmpty(DRAFT_STRATA)
    }

    @Test
    fun `rejects strata on simple site`() {
      assertThrows<IllegalArgumentException> {
        store.create(
            siteModel(
                siteType = DraftPlantingSiteType.Simple,
                strata = listOf(stratumModel("S1")),
            )
        )
      }

      assertTableEmpty(DRAFT_PLANTING_SITES)
      assertTableEmpty(DRAFT_STRATA)
    }

    @Test
    fun `rejects duplicate stratum names`() {
      assertThrows<IllegalArgumentException> {
        store.create(siteModel(strata = listOf(stratumModel("Dup"), stratumModel("Dup"))))
      }

      assertTableEmpty(DRAFT_PLANTING_SITES)
      assertTableEmpty(DRAFT_STRATA)
    }

    @Test
    fun `rejects client-supplied stratum and substratum ids`() {
      insertDraftPlantingSite()
      val stratumId = insertDraftStratum(name = "Existing")
      val substratumId = insertDraftSubstratum(name = "Existing")

      val expectedDrafts = dslContext.fetch(DRAFT_PLANTING_SITES)
      val expectedStrata = dslContext.fetch(DRAFT_STRATA)
      val expectedSubstrata = dslContext.fetch(DRAFT_SUBSTRATA)

      assertThrows<DraftStratumNotFoundException> {
        store.create(siteModel(strata = listOf(stratumModel("S1", id = stratumId))))
      }
      assertThrows<DraftSubstratumNotFoundException> {
        store.create(
            siteModel(
                strata =
                    listOf(
                        stratumModel("S1", substrata = listOf(substratumModel("A", substratumId)))
                    )
            )
        )
      }

      assertTableEquals(expectedDrafts)
      assertTableEquals(expectedStrata)
      assertTableEquals(expectedSubstrata)
    }

    @Test
    fun `rejects duplicate substratum names in a stratum`() {
      assertThrows<IllegalArgumentException> {
        store.create(
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

      assertTableEmpty(DRAFT_PLANTING_SITES)
    }

    @Test
    fun `throws exception if no permission to create drafts`() {
      every { user.canCreateDraftPlantingSite(any()) } returns false

      assertThrows<AccessDeniedException> { store.create(siteModel()) }
    }

    @Test
    fun `throws exception if project is in wrong organization`() {
      insertOrganization()
      insertProject()

      assertThrows<ProjectInDifferentOrganizationException> {
        store.create(siteModel(projectId = inserted.projectId))
      }
    }
  }

  @Nested
  inner class UpdateModel {
    @Test
    fun `update still throws not found for missing draft`() {
      assertThrows<DraftPlantingSiteNotFoundException> {
        store.updateFromModel(DraftPlantingSiteId(12345)) { it }
      }
    }

    @Test
    fun `updates scalar fields and audit columns`() {
      val projectId = insertProject()
      val draftId = insertDraftPlantingSite(exclusion = rectangle(10))
      val newTimeZone = timeZone

      clock.instant = Instant.EPOCH.plusSeconds(30)

      val expected =
          dslContext.fetchSingle(DRAFT_PLANTING_SITES).apply {
            boundary = rectangle(2000)
            boundarySource = "new source"
            description = "New description"
            exclusion = null
            modifiedTime = clock.instant
            name = "New name"
            this.projectId = projectId
            siteEditStep = "new step"
            timeZone = newTimeZone
          }

      store.updateFromModel(draftId) { model ->
        model.copy(
            description = "New description",
            name = "New name",
            projectId = projectId,
            timeZone = newTimeZone,
            map =
                model.map.copy(
                    boundary = rectangle(2000),
                    boundarySource = "new source",
                    exclusion = null,
                    siteEditStep = "new step",
                ),
        )
      }

      assertTableEquals(expected)
    }

    @Test
    fun `with null ids replaces all strata`() {
      val draftId = insertDraftWithStrata()
      val before = store.fetchModelById(draftId)
      val newStrata =
          listOf(
              stratumModel("S1", substrata = listOf(substratumModel("S1-A"))),
              stratumModel("S3"),
          )

      store.updateFromModel(draftId) { model ->
        model.copy(map = model.map.copy(strata = newStrata))
      }

      val after = store.fetchModelById(draftId)
      assertEquals(
          before.copy(map = before.map.copy(strata = newStrata)),
          after.withoutStratumIds(),
          "Draft with stratum IDs removed",
      )
      assertEquals(
          emptySet<Any>(),
          after.allStratumIds().intersect(before.allStratumIds()),
          "Old stratum and substratum IDs still present",
      )
    }

    @Test
    fun `with ids updates rows in place and deletes omitted ones`() {
      val draftId = insertDraftWithStrata()
      val existing = store.fetchModelById(draftId)
      val keptStratum = existing.map.strata[0]
      val keptSubstratum = keptStratum.substrata[0]
      val omittedStratumId = existing.map.strata[1].id!!
      val omittedSubstratumId = keptStratum.substrata[1].id!!
      val newBoundary = rectangle(300)

      val newStrata =
          listOf(
              keptStratum.copy(
                  name = "S1 renamed",
                  boundary = newBoundary,
                  initialPlantingDensity = BigDecimal(900),
                  substrata =
                      listOf(
                          keptSubstratum.copy(name = "A renamed", boundary = newBoundary),
                          substratumModel("New substratum"),
                      ),
              )
          )

      store.updateFromModel(draftId) { model ->
        model.copy(map = model.map.copy(strata = newStrata))
      }

      val newSubstratumId =
          dslContext.fetchSingle(DRAFT_SUBSTRATA, DRAFT_SUBSTRATA.NAME.eq("New substratum")).id!!
      val expected =
          existing.copy(
              map =
                  existing.map.copy(
                      strata =
                          listOf(
                              newStrata[0].copy(
                                  substrata =
                                      listOf(
                                          newStrata[0].substrata[0],
                                          newStrata[0].substrata[1].copy(id = newSubstratumId),
                                      )
                              )
                          )
                  )
          )

      assertEquals(expected, store.fetchModelById(draftId))
      assertTableEmpty(DRAFT_STRATA, where = DRAFT_STRATA.ID.eq(omittedStratumId))
      assertTableEmpty(DRAFT_SUBSTRATA, where = DRAFT_SUBSTRATA.ID.eq(omittedSubstratumId))
      assertTableEmpty(
          DRAFT_SUBSTRATA,
          where = DRAFT_SUBSTRATA.DRAFT_STRATUM_ID.eq(omittedStratumId),
      )
    }

    @Test
    fun `throws if stratum id belongs to another draft`() {
      val draftId = insertDraftWithStrata()
      val before = store.fetchModelById(draftId)
      insertDraftPlantingSite()
      val otherStratumId = insertDraftStratum(name = "Other")
      val otherBefore = store.fetchModelById(inserted.draftPlantingSiteId)

      assertThrows<DraftStratumNotFoundException> {
        store.updateFromModel(draftId) { model ->
          model.copy(
              name = "Changed",
              map =
                  model.map.copy(
                      strata = listOf(stratumModel("Stolen", id = otherStratumId)),
                  ),
          )
        }
      }

      assertEquals(before, store.fetchModelById(draftId), "Target draft should be unchanged")
      assertEquals(
          otherBefore,
          store.fetchModelById(inserted.draftPlantingSiteId),
          "Other draft should be unchanged",
      )
    }

    @Test
    fun `throws if substratum id belongs to another stratum`() {
      val draftId = insertDraftWithStrata()
      val before = store.fetchModelById(draftId)
      val stratum1 = before.map.strata[0]
      val stratum2 = before.map.strata[1]

      assertThrows<DraftSubstratumNotFoundException> {
        store.updateFromModel(draftId) { model ->
          model.copy(
              map =
                  model.map.copy(
                      strata =
                          listOf(
                              stratum1,
                              stratum2.copy(substrata = listOf(stratum1.substrata[0])),
                          )
                  )
          )
        }
      }

      assertEquals(before, store.fetchModelById(draftId), "Draft should be unchanged")
    }

    @Test
    fun `rejects duplicate stratum names`() {
      val draftId = insertDraftWithStrata()
      val before = store.fetchModelById(draftId)

      assertThrows<IllegalArgumentException> {
        store.updateFromModel(draftId) { model ->
          model.copy(
              map =
                  model.map.copy(
                      strata = listOf(stratumModel("Dup"), stratumModel("Dup")),
                  )
          )
        }
      }

      assertEquals(before, store.fetchModelById(draftId), "Draft should be unchanged")
    }

    @Test
    fun `rejects duplicate substratum names in a stratum`() {
      val draftId = insertDraftWithStrata()
      val before = store.fetchModelById(draftId)

      assertThrows<IllegalArgumentException> {
        store.updateFromModel(draftId) { model ->
          model.copy(
              map =
                  model.map.copy(
                      strata =
                          listOf(
                              stratumModel(
                                  "S1",
                                  substrata =
                                      listOf(substratumModel("Dup"), substratumModel("Dup")),
                              )
                          ),
                  )
          )
        }
      }

      assertEquals(before, store.fetchModelById(draftId), "Draft should be unchanged")
    }

    @Test
    fun `allows same substratum name in different strata`() {
      val draftId = insertDraftPlantingSite()
      val before = store.fetchModelById(draftId)
      val newStrata =
          listOf(
              stratumModel("S1", substrata = listOf(substratumModel("Same"))),
              stratumModel("S2", substrata = listOf(substratumModel("Same"))),
          )

      store.updateFromModel(draftId) { model ->
        model.copy(map = model.map.copy(strata = newStrata))
      }

      assertEquals(
          before.copy(map = before.map.copy(strata = newStrata)),
          store.fetchModelById(draftId).withoutStratumIds(),
      )
    }

    @Test
    fun `rejects existing substratum id under a new stratum`() {
      val draftId = insertDraftWithStrata()
      val before = store.fetchModelById(draftId)
      val existingSubstratum = before.map.strata[0].substrata[0]

      assertThrows<DraftSubstratumNotFoundException> {
        store.updateFromModel(draftId) { model ->
          model.copy(
              map =
                  model.map.copy(
                      strata =
                          listOf(
                              stratumModel("New", id = null, substrata = listOf(existingSubstratum))
                          ),
                  )
          )
        }
      }

      assertEquals(before, store.fetchModelById(draftId), "Draft should be unchanged")
    }

    @Test
    fun `rejects repeated stratum id`() {
      val draftId = insertDraftWithStrata()
      val before = store.fetchModelById(draftId)
      val stratum = before.map.strata[0]

      assertThrows<IllegalArgumentException> {
        store.updateFromModel(draftId) { model ->
          model.copy(
              map =
                  model.map.copy(
                      strata = listOf(stratum, stratum.copy(name = "Duplicate")),
                  )
          )
        }
      }

      assertEquals(before, store.fetchModelById(draftId), "Draft should be unchanged")
    }

    @Test
    fun `rejects repeated substratum id`() {
      val draftId = insertDraftWithStrata()
      val before = store.fetchModelById(draftId)
      val stratum = before.map.strata[0]
      val substratum = stratum.substrata[0]

      assertThrows<IllegalArgumentException> {
        store.updateFromModel(draftId) { model ->
          model.copy(
              map =
                  model.map.copy(
                      strata =
                          listOf(
                              stratum.copy(
                                  substrata =
                                      listOf(substratum, substratum.copy(name = "Duplicate"))
                              ),
                              before.map.strata[1],
                          )
                  )
          )
        }
      }

      assertEquals(before, store.fetchModelById(draftId), "Draft should be unchanged")
    }

    @Test
    fun `allows swapping two strata names`() {
      val draftId = insertDraftWithStrata()
      val before = store.fetchModelById(draftId)
      val stratum1 = before.map.strata[0]
      val stratum2 = before.map.strata[1]

      val newStrata = listOf(stratum1.copy(name = "S2"), stratum2.copy(name = "S1"))

      store.updateFromModel(draftId) { model ->
        model.copy(map = model.map.copy(strata = newStrata))
      }

      assertEquals(
          before.copy(map = before.map.copy(strata = newStrata)),
          store.fetchModelById(draftId),
      )
    }

    @Test
    fun `allows swapping two substrata names`() {
      val draftId = insertDraftWithStrata()
      val before = store.fetchModelById(draftId)
      val stratum = before.map.strata[0]
      val sub1 = stratum.substrata[0]
      val sub2 = stratum.substrata[1]

      val newStrata =
          listOf(
              stratum.copy(
                  substrata = listOf(sub1.copy(name = sub2.name), sub2.copy(name = sub1.name))
              ),
              before.map.strata[1],
          )

      store.updateFromModel(draftId) { model ->
        model.copy(map = model.map.copy(strata = newStrata))
      }

      assertEquals(
          before.copy(map = before.map.copy(strata = newStrata)),
          store.fetchModelById(draftId),
      )
    }

    @Test
    fun `rejects changing site type`() {
      val draftId = insertDraftPlantingSite(siteType = DraftPlantingSiteType.Simple)
      val before = store.fetchModelById(draftId)

      assertThrows<IllegalArgumentException> {
        store.updateFromModel(draftId) { model ->
          model.copy(map = model.map.copy(siteType = DraftPlantingSiteType.Detailed))
        }
      }

      assertEquals(before, store.fetchModelById(draftId), "Draft should be unchanged")
    }

    @Test
    fun `rejects strata on simple site`() {
      val draftId = insertDraftPlantingSite(siteType = DraftPlantingSiteType.Simple)

      assertThrows<IllegalArgumentException> {
        store.updateFromModel(draftId) { model ->
          model.copy(map = model.map.copy(strata = listOf(stratumModel("S1"))))
        }
      }

      assertTableEmpty(DRAFT_STRATA)
    }

    @Test
    fun `throws exception if no permission to update drafts`() {
      every { user.canUpdateDraftPlantingSite(any()) } returns false

      insertDraftPlantingSite()

      assertThrows<AccessDeniedException> {
        store.updateFromModel(inserted.draftPlantingSiteId) { it }
      }
    }

    @Test
    fun `throws exception if callback attempts to change read-only field`() {
      val draftId = insertDraftPlantingSite()
      val before = store.fetchModelById(draftId)
      val otherOrgId = insertOrganization()

      assertThrows<AccessDeniedException> {
        store.updateFromModel(draftId) { it.copy(organizationId = otherOrgId) }
      }

      assertEquals(before, store.fetchModelById(draftId), "Draft should be unchanged")
    }

    @Test
    fun `throws exception if project is in wrong organization`() {
      insertDraftPlantingSite()

      val otherOrgId = insertOrganization()
      insertProject(organizationId = otherOrgId)

      assertThrows<ProjectInDifferentOrganizationException> {
        store.updateFromModel(inserted.draftPlantingSiteId) {
          it.copy(projectId = inserted.projectId)
        }
      }
    }
  }
}
