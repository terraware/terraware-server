package com.terraformation.backend.report

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.GrowthForm
import com.terraformation.backend.db.default_schema.ReportId
import com.terraformation.backend.db.default_schema.ReportStatus
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionsRow
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.mockUser
import com.terraformation.backend.report.db.ReportStore
import com.terraformation.backend.report.model.LatestReportBodyModel
import com.terraformation.backend.report.model.ReportBodyModelV1
import com.terraformation.backend.report.model.ReportMetadata
import com.terraformation.backend.report.model.ReportModel
import com.terraformation.backend.seedbank.db.AccessionStore
import com.terraformation.backend.species.db.SpeciesStore
import com.terraformation.backend.tracking.db.PlantingSiteStore
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ReportServiceTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val messages = Messages()
  private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
  private val publisher = TestEventPublisher()
  private val parentStore by lazy { ParentStore(dslContext) }
  private val reportStore by lazy { ReportStore(clock, dslContext, objectMapper, reportsDao) }

  private val service by lazy {
    ReportService(
        AccessionStore(
            dslContext,
            mockk(),
            mockk(),
            mockk(),
            parentStore,
            mockk(),
            clock,
            messages,
            mockk(),
        ),
        FacilityStore(
            clock,
            mockk(),
            dslContext,
            publisher,
            facilitiesDao,
            messages,
            organizationsDao,
            storageLocationsDao,
        ),
        OrganizationStore(clock, dslContext, organizationsDao, publisher),
        PlantingSiteStore(clock, dslContext, plantingSitesDao),
        reportStore,
        SpeciesStore(clock, dslContext, speciesDao, speciesEcosystemTypesDao, speciesProblemsDao),
    )
  }

  @BeforeEach
  fun setUp() {
    every { user.canCreateReport(any()) } returns true
    every { user.canReadFacility(any()) } returns true
    every { user.canReadOrganization(any()) } returns true
    every { user.canReadPlantingSite(any()) } returns true
    every { user.canReadReport(any()) } returns true
    every { user.canUpdateReport(any()) } returns true
    every { user.organizationRoles } returns mapOf(organizationId to Role.Admin)

    insertUser()
    insertOrganization()
    insertOrganizationUser(user.userId, organizationId, Role.Admin)
  }

  @Nested
  inner class Create {
    @Test
    fun `populates all server-generated fields`() {
      val nurseryId = FacilityId(1)
      val plantingSiteId = PlantingSiteId(1)
      val seedBankId = FacilityId(2)
      val speciesId = insertSpecies(growthForm = GrowthForm.Shrub, scientificName = "My species")

      insertFacility(
          nurseryId,
          buildCompletedDate = LocalDate.of(2023, 3, 1),
          buildStartedDate = LocalDate.of(2023, 2, 1),
          capacity = 1000,
          type = FacilityType.Nursery,
      )

      insertFacility(
          seedBankId,
          operationStartedDate = LocalDate.of(2023, 4, 1),
          type = FacilityType.SeedBank,
      )
      insertAccession(
          AccessionsRow(
              facilityId = seedBankId,
              remainingQuantity = BigDecimal.TEN,
              remainingUnitsId = SeedQuantityUnits.Seeds))

      insertPlantingSite(id = plantingSiteId)
      val withdrawalId = insertWithdrawal(facilityId = nurseryId)
      val deliveryId = insertDelivery(plantingSiteId = plantingSiteId, withdrawalId = withdrawalId)
      insertPlanting(deliveryId = deliveryId, speciesId = speciesId)

      val expected =
          ReportModel(
              ReportBodyModelV1(
                  nurseries =
                      listOf(
                          ReportBodyModelV1.Nursery(
                              buildCompletedDate = LocalDate.of(2023, 3, 1),
                              buildCompletedDateEditable = false,
                              buildStartedDate = LocalDate.of(2023, 2, 1),
                              buildStartedDateEditable = false,
                              capacity = 1000,
                              id = nurseryId,
                              name = "Facility $nurseryId",
                              operationStartedDate = null,
                              operationStartedDateEditable = true,
                              selected = true,
                              workers = ReportBodyModelV1.Workers(),
                          ),
                      ),
                  organizationName = "Organization 1",
                  plantingSites =
                      listOf(
                          ReportBodyModelV1.PlantingSite(
                              id = plantingSiteId,
                              name = "Site $plantingSiteId",
                              selected = true,
                              species =
                                  listOf(
                                      ReportBodyModelV1.PlantingSite.Species(
                                          growthForm = GrowthForm.Shrub,
                                          id = speciesId,
                                          scientificName = "My species",
                                      )),
                              workers = ReportBodyModelV1.Workers(),
                          ),
                      ),
                  seedBanks =
                      listOf(
                          ReportBodyModelV1.SeedBank(
                              buildCompletedDate = null,
                              buildCompletedDateEditable = true,
                              buildStartedDate = null,
                              buildStartedDateEditable = true,
                              id = seedBankId,
                              name = "Facility $seedBankId",
                              operationStartedDate = LocalDate.of(2023, 4, 1),
                              operationStartedDateEditable = false,
                              totalSeedsStored = 10,
                              workers = ReportBodyModelV1.Workers(),
                          ),
                      ),
                  totalNurseries = 1,
                  totalPlantingSites = 1,
                  totalSeedBanks = 1,
              ),
              ReportMetadata(
                  ReportId(1),
                  organizationId = organizationId,
                  quarter = 1,
                  status = ReportStatus.New,
                  year = 1970,
              ))

      val created = service.create(organizationId)

      val actual = reportStore.fetchOneById(created.id)

      assertEquals(expected, actual)
    }
  }

  @Nested
  inner class FetchOneById {
    @Test
    fun `refreshes server-generated fields if report is not submitted`() {
      val reportId = insertReport()

      insertFacility(1, type = FacilityType.SeedBank)
      insertFacility(2, type = FacilityType.Nursery)
      insertPlantingSite(id = 1)

      val before = service.fetchOneById(reportId)
      val beforeBody = before.body as LatestReportBodyModel

      insertFacility(5, type = FacilityType.SeedBank, buildStartedDate = LocalDate.EPOCH)
      insertFacility(6, type = FacilityType.Nursery)
      insertPlantingSite(id = 3)
      organizationsDao.update(
          organizationsDao.fetchOneById(organizationId)!!.copy(name = "New name"))

      val expected =
          before.copy(
              body =
                  beforeBody.copy(
                      nurseries =
                          listOf(
                              beforeBody.nurseries[0],
                              beforeBody.nurseries[0].copy(id = FacilityId(6), name = "Facility 6"),
                          ),
                      organizationName = "New name",
                      plantingSites =
                          listOf(
                              beforeBody.plantingSites[0],
                              beforeBody.plantingSites[0].copy(
                                  id = PlantingSiteId(3), name = "Site 3"),
                          ),
                      seedBanks =
                          listOf(
                              beforeBody.seedBanks[0],
                              beforeBody.seedBanks[0].copy(
                                  buildStartedDate = LocalDate.EPOCH,
                                  buildStartedDateEditable = false,
                                  id = FacilityId(5),
                                  name = "Facility 5",
                              ),
                          ),
                      totalNurseries = 2,
                      totalPlantingSites = 2,
                      totalSeedBanks = 2,
                  ),
              metadata = before.metadata)

      val actual = service.fetchOneById(reportId)

      assertEquals(expected, actual)
      assertFalse((actual.body as ReportBodyModelV1).seedBanks[1].buildStartedDateEditable)
    }

    @Test
    fun `does not refresh server-generated fields if report was already submitted`() {
      val reportId = insertReport(submittedBy = user.userId)

      val expected = service.fetchOneById(reportId)

      insertFacility(5, type = FacilityType.SeedBank)
      insertFacility(6, type = FacilityType.Nursery)
      insertPlantingSite(id = 3)
      organizationsDao.update(
          organizationsDao.fetchOneById(organizationId)!!.copy(name = "New name"))

      val actual = service.fetchOneById(reportId)

      assertEquals(expected, actual)
    }
  }
}
