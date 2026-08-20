package com.terraformation.backend.eventlog.db

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
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
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
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_SPECIES
import com.terraformation.backend.eventlog.PersistentEvent
import com.terraformation.backend.eventlog.UpgradableEvent
import com.terraformation.backend.seedbank.event.ViabilityTestCreatedEventV1
import com.terraformation.backend.seedbank.event.ViabilityTestCreatedEventV2
import com.terraformation.backend.seedbank.event.ViabilityTestUpdatedEventV1
import com.terraformation.backend.seedbank.event.ViabilityTestUpdatedEventValues
import com.terraformation.backend.seedbank.event.WithdrawalCreatedEventV1
import com.terraformation.backend.seedbank.event.WithdrawalCreatedEventV2
import com.terraformation.backend.seedbank.event.WithdrawalCreatedEventV3
import com.terraformation.backend.seedbank.event.WithdrawalCreatedEventV4
import com.terraformation.backend.seedbank.event.WithdrawalUpdatedEventV1
import com.terraformation.backend.seedbank.event.WithdrawalUpdatedEventValues
import com.terraformation.backend.seedbank.grams
import com.terraformation.backend.seedbank.model.SeedQuantityModel
import com.terraformation.backend.seedbank.seeds
import com.terraformation.backend.tracking.event.BiomassQuadratSpeciesUpdatedEventV1
import com.terraformation.backend.tracking.event.BiomassSpeciesCreatedEvent
import com.terraformation.backend.tracking.event.BiomassSpeciesCreatedEventV1
import com.terraformation.backend.tracking.event.BiomassSpeciesCreatedEventV2
import com.terraformation.backend.tracking.event.BiomassSpeciesUpdatedEventV1
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

class EventUpgradeUtilsTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val objectMapper = jacksonObjectMapper()
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
  inner class WithdrawalCreatedEventUpgrades {
    private val withdrawalDate = LocalDate.of(2021, 1, 1)

    private lateinit var accessionId: AccessionId
    private lateinit var facilityId: FacilityId

    @BeforeEach
    fun setUpAccession() {
      insertOrganization()
      facilityId = insertFacility()
      accessionId = insertAccession()
    }

    @Nested
    inner class GetWithdrawalValuesMissingFromV1 {
      private lateinit var otherUserId: UserId

      @BeforeEach
      fun setUpOtherUser() {
        otherUserId = insertUser()
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
    }

    @Nested
    inner class UpgradeWithdrawalCreatedEventV3 {
      @Test
      fun `populates estimatedWeight from the current row for count-based withdrawals`() {
        val withdrawalId =
            insertSeedbankWithdrawal(
                batchId = null,
                estimatedWeightQuantity = BigDecimal(20),
                estimatedWeightUnitsId = SeedQuantityUnits.Grams,
                withdrawnQuantity = BigDecimal(10),
                withdrawnUnitsId = SeedQuantityUnits.Seeds,
            )

        testUpgrade(
            createdEventV3(withdrawalId, seeds(10)),
            createdEventV4(withdrawalId, seeds(10), estimatedWeight = grams(20)),
        )
      }

      @Test
      fun `does not populate estimatedWeight for weight-based withdrawals`() {
        val withdrawalId =
            insertSeedbankWithdrawal(
                batchId = null,
                estimatedWeightQuantity = BigDecimal(10),
                estimatedWeightUnitsId = SeedQuantityUnits.Grams,
                withdrawnQuantity = BigDecimal(10),
                withdrawnUnitsId = SeedQuantityUnits.Grams,
            )

        testUpgrade(
            createdEventV3(withdrawalId, grams(10), estimatedCount = 5),
            createdEventV4(withdrawalId, grams(10), estimatedCount = 5, estimatedWeight = null),
        )
      }

      @Test
      fun `does not populate estimatedWeight if the withdrawn quantity has changed`() {
        val withdrawalId =
            insertSeedbankWithdrawal(
                batchId = null,
                estimatedWeightQuantity = BigDecimal(100),
                estimatedWeightUnitsId = SeedQuantityUnits.Grams,
                withdrawnQuantity = BigDecimal(50),
                withdrawnUnitsId = SeedQuantityUnits.Seeds,
            )

        testUpgrade(
            createdEventV3(withdrawalId, seeds(10)),
            createdEventV4(withdrawalId, seeds(10), estimatedWeight = null),
        )
      }

      @Test
      fun `does not populate estimatedWeight if the withdrawal was deleted`() {
        val withdrawalId =
            insertSeedbankWithdrawal(
                batchId = null,
                estimatedWeightQuantity = BigDecimal(20),
                estimatedWeightUnitsId = SeedQuantityUnits.Grams,
                withdrawnQuantity = BigDecimal(10),
                withdrawnUnitsId = SeedQuantityUnits.Seeds,
            )

        withdrawalsDao.deleteById(withdrawalId)

        testUpgrade(
            createdEventV3(withdrawalId, seeds(10)),
            createdEventV4(withdrawalId, seeds(10), estimatedWeight = null),
        )
      }
    }

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
        withdrawnQuantity: SeedQuantityModel? = null,
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
            withdrawalId = withdrawalId,
            withdrawnByUserId = withdrawnByUserId,
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

    private fun createdEventV4(
        withdrawalId: WithdrawalId,
        withdrawnQuantity: SeedQuantityModel? = null,
        estimatedCount: Long? = null,
        estimatedWeight: SeedQuantityModel? = null,
    ) =
        WithdrawalCreatedEventV4(
            accessionId = accessionId,
            date = withdrawalDate,
            estimatedCount = estimatedCount,
            estimatedWeight = estimatedWeight,
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
      val knownSpeciesId = insertSpecies(scientificName = "Known species")
      val otherName = "Other species"

      listOf(
              createdEvent(
                  BiomassSpeciesId(1000),
                  knownSpeciesId,
                  null,
              ) to "Known species",
              createdEvent(BiomassSpeciesId(1001), null, otherName) to otherName,
          )
          .forEach { (event, expectedName) ->
            val upgraded = event.toNextVersion(insertEvent(event), eventUpgradeUtils)
            assertEquals(expectedName, upgraded.speciesName, "Upgraded name for $expectedName")
          }

      val biomassSpeciesId = insertObservationBiomassSpecies(speciesId = knownSpeciesId)
      val event = createdEvent(biomassSpeciesId, knownSpeciesId, null)
      insertEvent(event)
      dslContext
          .deleteFrom(OBSERVATION_BIOMASS_SPECIES)
          .where(OBSERVATION_BIOMASS_SPECIES.ID.eq(biomassSpeciesId))
          .execute()
      dslContext.deleteFrom(SPECIES).where(SPECIES.ID.eq(knownSpeciesId)).execute()

      val upgraded =
          eventLogStore.fetchLastById<BiomassSpeciesCreatedEvent>(biomassSpeciesId)!!.event
      assertEquals("$knownSpeciesId", upgraded.speciesName, "Name after row and species deletion")
    }

    @Test
    fun `upgrades biomass species updates from creation event or live row`() {
      val snapshotSpeciesId = insertSpecies(scientificName = "Current known name")
      val snapshotBiomassSpeciesId = insertObservationBiomassSpecies(speciesId = snapshotSpeciesId)
      insertEvent(
          BiomassSpeciesCreatedEventV2(
              biomassSpeciesId = snapshotBiomassSpeciesId,
              commonName = null,
              isInvasive = false,
              isThreatened = false,
              monitoringPlotId = inserted.monitoringPlotId,
              observationId = inserted.observationId,
              organizationId = inserted.organizationId,
              plantingSiteId = inserted.plantingSiteId,
              scientificName = null,
              speciesId = snapshotSpeciesId,
              speciesName = "Name captured when species was created",
          )
      )
      dslContext
          .deleteFrom(OBSERVATION_BIOMASS_SPECIES)
          .where(OBSERVATION_BIOMASS_SPECIES.ID.eq(snapshotBiomassSpeciesId))
          .execute()
      dslContext.deleteFrom(SPECIES).where(SPECIES.ID.eq(snapshotSpeciesId)).execute()

      val snapshotUpdate = speciesUpdatedEvent(snapshotBiomassSpeciesId)
      val snapshotUpgrade =
          snapshotUpdate.toNextVersion(insertEvent(snapshotUpdate), eventUpgradeUtils)
      assertEquals(
          "Name captured when species was created",
          snapshotUpgrade.speciesName,
          "Species name from creation event after row deletion",
      )
      assertEquals(
          snapshotSpeciesId,
          snapshotUpgrade.speciesId,
          "Species ID from creation event after row deletion",
      )

      val liveSpeciesId = insertSpecies(scientificName = "Known species")
      val liveBiomassSpeciesId = insertObservationBiomassSpecies(speciesId = liveSpeciesId)
      val liveUpdate = speciesUpdatedEvent(liveBiomassSpeciesId)
      val liveUpgrade = liveUpdate.toNextVersion(insertEvent(liveUpdate), eventUpgradeUtils)
      assertEquals(
          "Known species",
          liveUpgrade.speciesName,
          "Species name from live row without creation event",
      )
      assertEquals(
          liveSpeciesId,
          liveUpgrade.speciesId,
          "Species ID from live row without creation event",
      )

      val mergedSpeciesId = insertSpecies(scientificName = "Merged species")
      val mergedBiomassSpeciesId = insertObservationBiomassSpecies(speciesId = mergedSpeciesId)
      insertEvent(
          BiomassSpeciesCreatedEventV2(
              biomassSpeciesId = mergedBiomassSpeciesId,
              commonName = null,
              isInvasive = false,
              isThreatened = false,
              monitoringPlotId = inserted.monitoringPlotId,
              observationId = inserted.observationId,
              organizationId = inserted.organizationId,
              plantingSiteId = inserted.plantingSiteId,
              scientificName = "Other species",
              speciesId = null,
              speciesName = "Other species",
          )
      )

      val mergedUpdate = speciesUpdatedEvent(mergedBiomassSpeciesId)
      val mergedUpgrade = mergedUpdate.toNextVersion(insertEvent(mergedUpdate), eventUpgradeUtils)
      assertEquals("Merged species", mergedUpgrade.speciesName, "Species name from merged row")
      assertEquals(mergedSpeciesId, mergedUpgrade.speciesId, "Species ID from merged row")
    }

    @Test
    fun `upgrades quadrat species updates from creation event or live row`() {
      val otherName = "Other species"
      val biomassSpeciesId = insertObservationBiomassSpecies(scientificName = otherName)
      insertEvent(createdEvent(biomassSpeciesId, null, otherName))
      dslContext
          .deleteFrom(OBSERVATION_BIOMASS_SPECIES)
          .where(OBSERVATION_BIOMASS_SPECIES.ID.eq(biomassSpeciesId))
          .execute()

      val event = quadratSpeciesUpdatedEvent(biomassSpeciesId)
      val upgraded = event.toNextVersion(insertEvent(event), eventUpgradeUtils)
      assertEquals(
          otherName,
          upgraded.speciesName,
          "Species name from creation event after row deletion",
      )
      assertNull(
          upgraded.speciesId,
          "Species ID from creation event after row deletion",
      )

      val liveSpeciesId = insertSpecies(scientificName = "Known species")
      val liveBiomassSpeciesId = insertObservationBiomassSpecies(speciesId = liveSpeciesId)
      val liveEvent = quadratSpeciesUpdatedEvent(liveBiomassSpeciesId)
      val liveUpgrade = liveEvent.toNextVersion(insertEvent(liveEvent), eventUpgradeUtils)
      assertEquals(
          "Known species",
          liveUpgrade.speciesName,
          "Species name from live row without creation event",
      )
      assertEquals(
          liveSpeciesId,
          liveUpgrade.speciesId,
          "Species ID from live row without creation event",
      )
    }

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
            monitoringPlotId = inserted.monitoringPlotId,
            observationId = inserted.observationId,
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
            monitoringPlotId = inserted.monitoringPlotId,
            observationId = inserted.observationId,
            organizationId = inserted.organizationId,
            plantingSiteId = inserted.plantingSiteId,
        )

    private fun quadratSpeciesUpdatedEvent(biomassSpeciesId: BiomassSpeciesId) =
        BiomassQuadratSpeciesUpdatedEventV1(
            changedFrom = BiomassQuadratSpeciesUpdatedEventV1.Values(1),
            changedTo = BiomassQuadratSpeciesUpdatedEventV1.Values(2),
            biomassSpeciesId = biomassSpeciesId,
            monitoringPlotId = inserted.monitoringPlotId,
            observationId = inserted.observationId,
            organizationId = inserted.organizationId,
            plantingSiteId = inserted.plantingSiteId,
            position = ObservationPlotPosition.SouthwestCorner,
        )
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
