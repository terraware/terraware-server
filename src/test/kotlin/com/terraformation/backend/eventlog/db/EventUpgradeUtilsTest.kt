package com.terraformation.backend.eventlog.db

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.customer.event.OrganizationCreatedEventV1
import com.terraformation.backend.customer.event.OrganizationRenamedEventV1
import com.terraformation.backend.customer.event.OrganizationRenamedEventV2
import com.terraformation.backend.customer.event.ProjectCreatedEventV1
import com.terraformation.backend.customer.event.ProjectRenamedEventV1
import com.terraformation.backend.customer.event.ProjectRenamedEventV2
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.EventLogId
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.seedbank.ViabilityTestType
import com.terraformation.backend.db.seedbank.WithdrawalId
import com.terraformation.backend.db.seedbank.WithdrawalPurpose
import com.terraformation.backend.db.tracking.BiomassSpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_SPECIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_SPECIES
import com.terraformation.backend.db.tracking.tables.references.RECORDED_TREES
import com.terraformation.backend.eventlog.PersistentEvent
import com.terraformation.backend.eventlog.UpgradableEvent
import com.terraformation.backend.seedbank.event.ViabilityTestCreatedEventV1
import com.terraformation.backend.seedbank.event.ViabilityTestCreatedEventV2
import com.terraformation.backend.seedbank.event.ViabilityTestUpdatedEventV1
import com.terraformation.backend.seedbank.event.ViabilityTestUpdatedEventValues
import com.terraformation.backend.seedbank.event.WithdrawalCreatedEventV1
import com.terraformation.backend.seedbank.event.WithdrawalCreatedEventV2
import com.terraformation.backend.seedbank.event.WithdrawalCreatedEventV3
import com.terraformation.backend.seedbank.event.WithdrawalUpdatedEventV1
import com.terraformation.backend.seedbank.event.WithdrawalUpdatedEventValues
import com.terraformation.backend.seedbank.grams
import com.terraformation.backend.seedbank.model.SeedQuantityModel
import com.terraformation.backend.seedbank.seeds
import com.terraformation.backend.tracking.event.BiomassQuadratSpeciesUpdatedEventV1
import com.terraformation.backend.tracking.event.BiomassQuadratSpeciesUpdatedEventV2
import com.terraformation.backend.tracking.event.BiomassSpeciesCreatedEvent
import com.terraformation.backend.tracking.event.BiomassSpeciesCreatedEventV1
import com.terraformation.backend.tracking.event.BiomassSpeciesUpdatedEventV1
import com.terraformation.backend.tracking.event.BiomassSpeciesUpdatedEventV2
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class EventUpgradeUtilsTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
  private val eventLogStore: EventLogStore by lazy {
    EventLogStore(clock, dslContext, objectMapper)
  }
  private val eventUpgradeUtils: EventUpgradeUtils by lazy {
    EventUpgradeUtils(dslContext, eventLogStore)
  }

  private val organizationId = OrganizationId(1)
  private val projectId = ProjectId(2)

  @Nested
  inner class GetPreviousOrganizationName {
    @Test
    fun `upgrades first OrganizationRenamedEventV1`() {
      insertEvent(OrganizationCreatedEventV1(organizationId, "Old name"))

      testUpgrade(
          OrganizationRenamedEventV1(organizationId, "New name"),
          OrganizationRenamedEventV2(
              changedFrom = OrganizationRenamedEventV2.Values("Old name"),
              changedTo = OrganizationRenamedEventV2.Values("New name"),
              organizationId = organizationId,
          ),
      )
    }

    @Test
    fun `upgrades second OrganizationRenamedEventV1`() {
      insertEvent(OrganizationCreatedEventV1(organizationId, "Old name"))
      insertEvent(OrganizationRenamedEventV1(organizationId, "Middle name"))

      testUpgrade(
          OrganizationRenamedEventV1(organizationId, "New name"),
          OrganizationRenamedEventV2(
              changedFrom = OrganizationRenamedEventV2.Values("Middle name"),
              changedTo = OrganizationRenamedEventV2.Values("New name"),
              organizationId = organizationId,
          ),
      )
    }

    // This could happen if there are two V1 renames and we're querying them for the first time; the
    // first one would be upgraded and written to the database before the second one was upgraded.
    @Test
    fun `upgrades OrganizationRenamedEventV1 that follows an already-upgraded rename`() {
      insertEvent(OrganizationCreatedEventV1(organizationId, "Old name"))
      insertEvent(
          OrganizationRenamedEventV2(
              changedFrom = OrganizationRenamedEventV2.Values("Old name"),
              changedTo = OrganizationRenamedEventV2.Values("Middle name"),
              organizationId = organizationId,
          )
      )

      testUpgrade(
          OrganizationRenamedEventV1(organizationId, "New name"),
          OrganizationRenamedEventV2(
              changedFrom = OrganizationRenamedEventV2.Values("Middle name"),
              changedTo = OrganizationRenamedEventV2.Values("New name"),
              organizationId = organizationId,
          ),
      )
    }
  }

  @Nested
  inner class GetPreviousProjectName {
    @Test
    fun `upgrades first ProjectRenamedEventV1`() {
      insertEvent(
          ProjectCreatedEventV1(
              countryCode = null,
              name = "Old name",
              organizationId = organizationId,
              projectId = projectId,
          )
      )

      testUpgrade(
          ProjectRenamedEventV1("New name", organizationId, projectId),
          ProjectRenamedEventV2(
              changedFrom = ProjectRenamedEventV2.Values("Old name"),
              changedTo = ProjectRenamedEventV2.Values("New name"),
              organizationId = organizationId,
              projectId = projectId,
          ),
      )
    }

    @Test
    fun `upgrades second ProjectRenamedEventV1`() {
      insertEvent(
          ProjectCreatedEventV1(
              countryCode = null,
              name = "Old name",
              organizationId = organizationId,
              projectId = projectId,
          )
      )
      insertEvent(ProjectRenamedEventV1("Middle name", organizationId, projectId))

      testUpgrade(
          ProjectRenamedEventV1("New name", organizationId, projectId),
          ProjectRenamedEventV2(
              changedFrom = ProjectRenamedEventV2.Values("Middle name"),
              changedTo = ProjectRenamedEventV2.Values("New name"),
              organizationId = organizationId,
              projectId = projectId,
          ),
      )
    }

    // This could happen if there are two V1 renames and we're querying them for the first time; the
    // first one would be upgraded and written to the database before the second one was upgraded.
    @Test
    fun `upgrades ProjectRenamedEventV1 that follows an already-upgraded rename`() {
      insertEvent(
          ProjectCreatedEventV1(
              countryCode = null,
              name = "Old name",
              organizationId = organizationId,
              projectId = projectId,
          )
      )
      insertEvent(
          ProjectRenamedEventV2(
              changedFrom = ProjectRenamedEventV2.Values("Old name"),
              changedTo = ProjectRenamedEventV2.Values("Middle name"),
              organizationId = organizationId,
              projectId = projectId,
          )
      )

      testUpgrade(
          ProjectRenamedEventV1("New name", organizationId, projectId),
          ProjectRenamedEventV2(
              changedFrom = ProjectRenamedEventV2.Values("Middle name"),
              changedTo = ProjectRenamedEventV2.Values("New name"),
              organizationId = organizationId,
              projectId = projectId,
          ),
      )
    }
  }

  @Nested
  inner class GetWithdrawalValuesMissingFromV1 {
    private val withdrawalDate = LocalDate.of(2021, 1, 1)

    private lateinit var accessionId: AccessionId
    private lateinit var facilityId: FacilityId
    private lateinit var otherUserId: UserId

    @BeforeEach
    fun setUpAccession() {
      otherUserId = insertUser()
      insertOrganization()
      facilityId = insertFacility()
      accessionId = insertAccession()
    }

    @Test
    fun `uses the current withdrawal row when no later edit touched the fields`() {
      val viabilityTestId = insertViabilityTest()
      val withdrawalId =
          insertSeedbankWithdrawal(
              batchId = null,
              destination = "Lab",
              purpose = WithdrawalPurpose.ViabilityTesting,
              viabilityTestId = viabilityTestId,
              withdrawnBy = otherUserId,
          )

      testUpgrade(
          createdEventV1(withdrawalId),
          createdEventV2(
              withdrawalId,
              destination = "Lab",
              viabilityTestId = viabilityTestId,
              withdrawnByUserId = otherUserId,
          ),
      )
    }

    @Test
    fun `uses the value from before the earliest edit that changed the field`() {
      val withdrawalId =
          insertSeedbankWithdrawal(
              batchId = null,
              destination = "Greenhouse",
              withdrawnBy = otherUserId,
          )
      insertEvent(createdEventV1(withdrawalId))

      insertEvent(
          updatedEvent(
              withdrawalId,
              changedFrom = WithdrawalUpdatedEventValues(destination = "Lab"),
              changedTo = WithdrawalUpdatedEventValues(destination = "Nursery"),
          )
      )
      insertEvent(
          updatedEvent(
              withdrawalId,
              changedFrom = WithdrawalUpdatedEventValues(destination = "Nursery"),
              changedTo = WithdrawalUpdatedEventValues(destination = "Greenhouse"),
          )
      )

      assertEquals(
          "Lab",
          upgrade(withdrawalId).destination,
          "Should use the changedFrom of the first edit, not the current row",
      )
    }

    @Test
    fun `ignores later edits that left the field alone`() {
      val withdrawalId =
          insertSeedbankWithdrawal(
              batchId = null,
              destination = "Greenhouse",
              withdrawnBy = user.userId,
          )
      insertEvent(createdEventV1(withdrawalId))

      // Only touches withdrawnByUserId, so it says nothing about the destination.
      insertEvent(
          updatedEvent(
              withdrawalId,
              changedFrom = WithdrawalUpdatedEventValues(withdrawnByUserId = user.userId),
              changedTo = WithdrawalUpdatedEventValues(withdrawnByUserId = otherUserId),
          )
      )
      insertEvent(
          updatedEvent(
              withdrawalId,
              changedFrom = WithdrawalUpdatedEventValues(destination = "Lab"),
              changedTo = WithdrawalUpdatedEventValues(destination = "Greenhouse"),
          )
      )

      val upgraded = upgrade(withdrawalId)

      assertEquals("Lab", upgraded.destination, "destination")
      assertEquals(user.userId, upgraded.withdrawnByUserId, "withdrawnByUserId")
    }

    @Test
    fun `treats a field that an edit populated as null at creation time`() {
      val withdrawalId =
          insertSeedbankWithdrawal(batchId = null, destination = "Lab", withdrawnBy = user.userId)
      insertEvent(createdEventV1(withdrawalId))

      insertEvent(
          updatedEvent(
              withdrawalId,
              changedFrom = WithdrawalUpdatedEventValues(destination = null),
              changedTo = WithdrawalUpdatedEventValues(destination = "Lab"),
          )
      )

      assertNull(
          upgrade(withdrawalId).destination,
          "Destination was empty at creation time even though the row has one now",
      )
    }

    @Test
    fun `upgrades a deleted withdrawal`() {
      val withdrawalId = insertSeedbankWithdrawal(batchId = null, destination = "Lab")
      insertEvent(createdEventV1(withdrawalId))

      withdrawalsDao.deleteById(withdrawalId)

      assertEquals(createdEventV2(withdrawalId), upgrade(withdrawalId))
    }

    private fun upgrade(withdrawalId: WithdrawalId) =
        eventUpgradeUtils.upgrade(createdEventV1(withdrawalId))

    private fun createdEventV1(withdrawalId: WithdrawalId) =
        WithdrawalCreatedEventV1(
            accessionId = accessionId,
            date = withdrawalDate,
            facilityId = facilityId,
            organizationId = inserted.organizationId,
            purpose = WithdrawalPurpose.Other,
            withdrawalId = withdrawalId,
        )

    private fun createdEventV2(
        withdrawalId: WithdrawalId,
        destination: String? = null,
        viabilityTestId: ViabilityTestId? = null,
        withdrawnByUserId: UserId? = null,
    ) =
        WithdrawalCreatedEventV2(
            accessionId = accessionId,
            date = withdrawalDate,
            destination = destination,
            facilityId = facilityId,
            organizationId = inserted.organizationId,
            purpose = WithdrawalPurpose.Other,
            viabilityTestId = viabilityTestId,
            withdrawnByUserId = withdrawnByUserId,
            withdrawalId = withdrawalId,
        )

    private fun updatedEvent(
        withdrawalId: WithdrawalId,
        changedFrom: WithdrawalUpdatedEventValues,
        changedTo: WithdrawalUpdatedEventValues,
    ) =
        WithdrawalUpdatedEventV1(
            changedFrom = changedFrom,
            changedTo = changedTo,
            withdrawalId = withdrawalId,
            accessionId = accessionId,
            facilityId = facilityId,
            organizationId = inserted.organizationId,
        )
  }

  @Nested
  inner class UpgradeWithdrawalCreatedEventV2 {
    private val withdrawalDate = LocalDate.of(2021, 1, 1)

    private lateinit var accessionId: AccessionId
    private lateinit var facilityId: FacilityId

    @BeforeEach
    fun setUpAccession() {
      insertOrganization()
      facilityId = insertFacility()
      accessionId = insertAccession()
    }

    @Test
    fun `populates estimatedCount from the current row for weight-based withdrawals`() {
      val withdrawalId =
          insertSeedbankWithdrawal(
              batchId = null,
              estimatedCount = 42,
              withdrawnQuantity = BigDecimal(10),
              withdrawnUnitsId = SeedQuantityUnits.Grams,
          )

      testUpgrade(
          createdEventV2(withdrawalId, grams(10)),
          createdEventV3(withdrawalId, grams(10), estimatedCount = 42),
      )
    }

    @Test
    fun `does not populate estimatedCount for count-based withdrawals`() {
      val withdrawalId =
          insertSeedbankWithdrawal(
              batchId = null,
              estimatedCount = 42,
              withdrawnQuantity = BigDecimal(10),
              withdrawnUnitsId = SeedQuantityUnits.Seeds,
          )

      testUpgrade(
          createdEventV2(withdrawalId, seeds(10)),
          createdEventV3(withdrawalId, seeds(10), estimatedCount = null),
      )
    }

    @Test
    fun `does not populate estimatedCount if the withdrawal was deleted`() {
      val withdrawalId =
          insertSeedbankWithdrawal(
              batchId = null,
              estimatedCount = 42,
              withdrawnQuantity = BigDecimal(10),
              withdrawnUnitsId = SeedQuantityUnits.Grams,
          )

      withdrawalsDao.deleteById(withdrawalId)

      testUpgrade(
          createdEventV2(withdrawalId, grams(10)),
          createdEventV3(withdrawalId, grams(10), estimatedCount = null),
      )
    }

    private fun createdEventV2(
        withdrawalId: WithdrawalId,
        withdrawnQuantity: SeedQuantityModel? = null,
    ) =
        WithdrawalCreatedEventV2(
            accessionId = accessionId,
            date = withdrawalDate,
            facilityId = facilityId,
            organizationId = inserted.organizationId,
            purpose = WithdrawalPurpose.Other,
            withdrawalId = withdrawalId,
            withdrawnQuantity = withdrawnQuantity,
        )

    private fun createdEventV3(
        withdrawalId: WithdrawalId,
        withdrawnQuantity: SeedQuantityModel? = null,
        estimatedCount: Long? = null,
    ) =
        WithdrawalCreatedEventV3(
            accessionId = accessionId,
            date = withdrawalDate,
            estimatedCount = estimatedCount,
            facilityId = facilityId,
            organizationId = inserted.organizationId,
            purpose = WithdrawalPurpose.Other,
            withdrawalId = withdrawalId,
            withdrawnQuantity = withdrawnQuantity,
        )
  }

  @Nested
  inner class GetViabilityTestValuesMissingFromV1 {
    private lateinit var accessionId: AccessionId
    private lateinit var facilityId: FacilityId

    @BeforeEach
    fun setUpAccession() {
      insertOrganization()
      facilityId = insertFacility()
      accessionId = insertAccession()
    }

    @Test
    fun `uses the current viability test row when no later edit touched the fields`() {
      val viabilityTestId =
          insertViabilityTest(notes = "initial notes", staffResponsible = "Some Person")

      testUpgrade(
          createdEventV1(viabilityTestId),
          createdEventV2(
              viabilityTestId,
              notes = "initial notes",
              staffResponsible = "Some Person",
          ),
      )
    }

    @Test
    fun `uses the value from before the earliest edit that changed the field`() {
      val viabilityTestId = insertViabilityTest(notes = "current notes")
      val createdEventLogId = insertEvent(createdEventV1(viabilityTestId))

      insertEvent(
          updatedEvent(
              viabilityTestId,
              changedFrom = ViabilityTestUpdatedEventValues(notes = "initial notes"),
              changedTo = ViabilityTestUpdatedEventValues(notes = "middle notes"),
          )
      )
      insertEvent(
          updatedEvent(
              viabilityTestId,
              changedFrom = ViabilityTestUpdatedEventValues(notes = "middle notes"),
              changedTo = ViabilityTestUpdatedEventValues(notes = "current notes"),
          )
      )

      assertEquals(
          "initial notes",
          eventUpgradeUtils
              .getViabilityTestValuesMissingFromV1(viabilityTestId, createdEventLogId)
              .notes,
      )
    }

    @Test
    fun `returns nulls if the viability test has been deleted`() {
      val viabilityTestId = insertViabilityTest(notes = "notes")
      val createdEventLogId = insertEvent(createdEventV1(viabilityTestId))

      viabilityTestsDao.deleteById(viabilityTestId)

      assertEquals(
          EventUpgradeUtils.ViabilityTestValuesMissingFromV1(null, null),
          eventUpgradeUtils.getViabilityTestValuesMissingFromV1(viabilityTestId, createdEventLogId),
      )
    }

    private fun createdEventV1(viabilityTestId: ViabilityTestId) =
        ViabilityTestCreatedEventV1(
            testType = ViabilityTestType.Lab,
            viabilityTestId = viabilityTestId,
            accessionId = accessionId,
            facilityId = facilityId,
            organizationId = inserted.organizationId,
        )

    private fun createdEventV2(
        viabilityTestId: ViabilityTestId,
        notes: String? = null,
        staffResponsible: String? = null,
    ) =
        ViabilityTestCreatedEventV2(
            testType = ViabilityTestType.Lab,
            notes = notes,
            staffResponsible = staffResponsible,
            viabilityTestId = viabilityTestId,
            accessionId = accessionId,
            facilityId = facilityId,
            organizationId = inserted.organizationId,
        )

    private fun updatedEvent(
        viabilityTestId: ViabilityTestId,
        changedFrom: ViabilityTestUpdatedEventValues,
        changedTo: ViabilityTestUpdatedEventValues,
    ) =
        ViabilityTestUpdatedEventV1(
            changedFrom = changedFrom,
            changedTo = changedTo,
            viabilityTestId = viabilityTestId,
            accessionId = accessionId,
            facilityId = facilityId,
            organizationId = inserted.organizationId,
        )
  }

  @Nested
  inner class BiomassSpeciesEventUpgrades {
    private lateinit var monitoringPlotId: MonitoringPlotId
    private lateinit var observationId: ObservationId

    @BeforeEach
    fun setUpBiomassObservation() {
      insertOrganization()
      insertPlantingSite()
      insertStratum()
      insertSubstratum()
      monitoringPlotId = insertMonitoringPlot()
      observationId = insertObservation(observationType = ObservationType.BiomassMeasurements)
      insertObservationPlot()
      insertObservationBiomassDetails()
      insertObservationBiomassQuadratDetails(
          observationId = observationId,
          monitoringPlotId = monitoringPlotId,
          position = ObservationPlotPosition.SouthwestCorner,
      )
    }

    @Test
    fun `upgrades created events from their own fields including after row deletion`() {
      val catalogSpeciesId = insertSpecies(scientificName = "Catalog species")
      val catalogSpeciesIdAfterDeletion =
          insertSpecies(scientificName = "Catalog species after deletion")
      val otherName = "Other species"
      val otherNameAfterDeletion = "Other species after deletion"

      assertCreatedUpgrade(
          createdEvent(insertBiomassSpeciesRow(catalogSpeciesId, null), catalogSpeciesId, null),
          "Catalog species",
      )
      assertCreatedUpgrade(
          createdEvent(insertBiomassSpeciesRow(null, otherName), null, otherName),
          otherName,
      )
      assertCreatedUpgradeAfterDeletingRow(
          catalogSpeciesIdAfterDeletion,
          null,
          "Catalog species after deletion",
      )
      assertCreatedUpgradeAfterDeletingRow(null, otherNameAfterDeletion, otherNameAfterDeletion)
    }

    @Test
    fun `upgrades biomass species updates using creation event or live row`() {
      val catalogSpeciesId = insertSpecies(scientificName = "Catalog species")
      val catalogSpeciesIdAfterDeletion =
          insertSpecies(scientificName = "Catalog species after deletion")
      val catalogSpeciesIdWithoutCreation =
          insertSpecies(scientificName = "Catalog species without creation event")
      val otherName = "Other species"
      val otherNameWithoutCreation = "Other species without creation event"

      assertUpdateUpgrade(
          ::speciesUpdatedEvent,
          catalogSpeciesId,
          null,
          "Catalog species",
      )
      assertUpdateUpgrade(::speciesUpdatedEvent, null, otherName, otherName)
      assertUpdateUpgrade(
          ::speciesUpdatedEvent,
          catalogSpeciesIdAfterDeletion,
          null,
          "Catalog species after deletion",
          deleteBiomassSpeciesRow = true,
      )
      assertUpdateUpgrade(
          ::speciesUpdatedEvent,
          catalogSpeciesIdWithoutCreation,
          null,
          "Catalog species without creation event",
          includeCreatedEvent = false,
      )
      assertUpdateUpgrade(
          ::speciesUpdatedEvent,
          null,
          otherNameWithoutCreation,
          otherNameWithoutCreation,
          includeCreatedEvent = false,
      )
    }

    @Test
    fun `upgrades quadrat species updates using creation event or live row`() {
      val catalogSpeciesId = insertSpecies(scientificName = "Catalog species")
      val catalogSpeciesIdAfterDeletion =
          insertSpecies(scientificName = "Catalog species after deletion")
      val catalogSpeciesIdWithoutCreation =
          insertSpecies(scientificName = "Catalog species without creation event")
      val otherName = "Other species"
      val otherNameWithoutCreation = "Other species without creation event"

      assertUpdateUpgrade(
          ::quadratSpeciesUpdatedEvent,
          catalogSpeciesId,
          null,
          "Catalog species",
      )
      assertUpdateUpgrade(::quadratSpeciesUpdatedEvent, null, otherName, otherName)
      assertUpdateUpgrade(
          ::quadratSpeciesUpdatedEvent,
          catalogSpeciesIdAfterDeletion,
          null,
          "Catalog species after deletion",
          deleteBiomassSpeciesRow = true,
      )
      assertUpdateUpgrade(
          ::quadratSpeciesUpdatedEvent,
          catalogSpeciesIdWithoutCreation,
          null,
          "Catalog species without creation event",
          includeCreatedEvent = false,
      )
      assertUpdateUpgrade(
          ::quadratSpeciesUpdatedEvent,
          null,
          otherNameWithoutCreation,
          otherNameWithoutCreation,
          includeCreatedEvent = false,
      )
    }

    private fun assertCreatedUpgrade(
        event: BiomassSpeciesCreatedEventV1,
        expectedSpeciesName: String,
    ) {
      val upgraded = event.toNextVersion(insertEvent(event), eventUpgradeUtils)
      assertEquals(expectedSpeciesName, upgraded.speciesName)
    }

    private fun assertCreatedUpgradeAfterDeletingRow(
        speciesId: SpeciesId?,
        scientificName: String?,
        expectedSpeciesName: String,
    ) {
      val biomassSpeciesId = insertBiomassSpeciesRow(speciesId, scientificName)
      insertEvent(createdEvent(biomassSpeciesId, speciesId, scientificName))
      deleteBiomassSpeciesWithChildren(biomassSpeciesId)

      val upgraded =
          eventLogStore.fetchLastById<BiomassSpeciesCreatedEvent>(biomassSpeciesId)!!.event
      assertEquals(expectedSpeciesName, upgraded.speciesName)
    }

    private fun assertUpdateUpgrade(
        makeEvent: (BiomassSpeciesId) -> UpgradableEvent,
        speciesId: SpeciesId?,
        scientificName: String?,
        expectedSpeciesName: String,
        deleteBiomassSpeciesRow: Boolean = false,
        includeCreatedEvent: Boolean = true,
    ) {
      val biomassSpeciesId = insertBiomassSpeciesRow(speciesId, scientificName)
      if (includeCreatedEvent) {
        insertEvent(createdEvent(biomassSpeciesId, speciesId, scientificName))
      }

      val event = makeEvent(biomassSpeciesId)
      val eventLogId = insertEvent(event)
      if (deleteBiomassSpeciesRow) {
        deleteBiomassSpeciesWithChildren(biomassSpeciesId)
      }

      when (val upgraded = event.toNextVersion(eventLogId, eventUpgradeUtils)) {
        is BiomassSpeciesUpdatedEventV2 -> {
          assertEquals(expectedSpeciesName, upgraded.speciesName)
          assertEquals(speciesId, upgraded.speciesId)
        }
        is BiomassQuadratSpeciesUpdatedEventV2 -> {
          assertEquals(expectedSpeciesName, upgraded.speciesName)
          assertEquals(speciesId, upgraded.speciesId)
        }
        else -> error("Unexpected upgraded event type: ${upgraded.javaClass.name}")
      }
    }

    private fun insertBiomassSpeciesRow(
        speciesId: SpeciesId?,
        scientificName: String?,
    ): BiomassSpeciesId =
        insertObservationBiomassSpecies(
            observationId = observationId,
            monitoringPlotId = monitoringPlotId,
            speciesId = speciesId,
            scientificName = scientificName,
        )

    private fun createdEvent(
        biomassSpeciesId: BiomassSpeciesId,
        speciesId: SpeciesId?,
        scientificName: String?,
    ) =
        BiomassSpeciesCreatedEventV1(
            biomassSpeciesId = biomassSpeciesId,
            commonName = null,
            isInvasive = false,
            isThreatened = false,
            monitoringPlotId = monitoringPlotId,
            observationId = observationId,
            organizationId = inserted.organizationId,
            plantingSiteId = inserted.plantingSiteId,
            scientificName = scientificName,
            speciesId = speciesId,
        )

    private fun speciesUpdatedEvent(biomassSpeciesId: BiomassSpeciesId) =
        BiomassSpeciesUpdatedEventV1(
            changedFrom = BiomassSpeciesUpdatedEventV1.Values(false, false),
            changedTo = BiomassSpeciesUpdatedEventV1.Values(true, true),
            biomassSpeciesId = biomassSpeciesId,
            monitoringPlotId = monitoringPlotId,
            observationId = observationId,
            organizationId = inserted.organizationId,
            plantingSiteId = inserted.plantingSiteId,
        )

    private fun quadratSpeciesUpdatedEvent(biomassSpeciesId: BiomassSpeciesId) =
        BiomassQuadratSpeciesUpdatedEventV1(
            changedFrom = BiomassQuadratSpeciesUpdatedEventV1.Values(1),
            changedTo = BiomassQuadratSpeciesUpdatedEventV1.Values(2),
            biomassSpeciesId = biomassSpeciesId,
            monitoringPlotId = monitoringPlotId,
            observationId = observationId,
            organizationId = inserted.organizationId,
            plantingSiteId = inserted.plantingSiteId,
            position = ObservationPlotPosition.SouthwestCorner,
        )

    private fun deleteBiomassSpeciesWithChildren(biomassSpeciesId: BiomassSpeciesId) {
      insertRecordedTree(
          observationId = observationId,
          monitoringPlotId = monitoringPlotId,
          biomassSpeciesId = biomassSpeciesId,
          diameterAtBreastHeightCm = BigDecimal.ONE,
          pointOfMeasurementM = BigDecimal("1.3"),
          heightM = BigDecimal.TEN,
      )
      insertObservationBiomassQuadratSpecies(
          observationId = observationId,
          monitoringPlotId = monitoringPlotId,
          position = ObservationPlotPosition.SouthwestCorner,
          biomassSpeciesId = biomassSpeciesId,
          abundanceCount = 1,
      )

      dslContext
          .deleteFrom(RECORDED_TREES)
          .where(RECORDED_TREES.BIOMASS_SPECIES_ID.eq(biomassSpeciesId))
          .execute()
      dslContext
          .deleteFrom(OBSERVATION_BIOMASS_QUADRAT_SPECIES)
          .where(OBSERVATION_BIOMASS_QUADRAT_SPECIES.BIOMASS_SPECIES_ID.eq(biomassSpeciesId))
          .execute()
      dslContext
          .deleteFrom(OBSERVATION_BIOMASS_SPECIES)
          .where(OBSERVATION_BIOMASS_SPECIES.ID.eq(biomassSpeciesId))
          .execute()
    }
  }

  private fun insertEvent(event: PersistentEvent): EventLogId {
    clock.instant = clock.instant.plusSeconds(1)
    return eventLogStore.insertEvent(event)
  }

  private fun testUpgrade(oldEvent: UpgradableEvent, expected: PersistentEvent) {
    val eventLogId = insertEvent(oldEvent)
    val upgradedEvent = oldEvent.toNextVersion(eventLogId, eventUpgradeUtils)

    assertEquals(expected, upgradedEvent)
  }
}
