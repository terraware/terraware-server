package com.terraformation.backend.seedbank.db.accessionStore

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.IdentifierGenerator
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.seedbank.ProcessingMethod
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.db.seedbank.tables.references.ACCESSION_QUANTITY_HISTORY
import com.terraformation.backend.db.seedbank.tables.references.BAGS
import com.terraformation.backend.db.seedbank.tables.references.GEOLOCATIONS
import com.terraformation.backend.db.seedbank.tables.references.VIABILITY_TESTS
import com.terraformation.backend.db.seedbank.tables.references.WITHDRAWALS
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.mockUser
import com.terraformation.backend.seedbank.api.SeedQuantityPayload
import com.terraformation.backend.seedbank.api.UpdateAccessionRequestPayload
import com.terraformation.backend.seedbank.api.ViabilityTestPayload
import com.terraformation.backend.seedbank.api.ViabilityTestTypeV1
import com.terraformation.backend.seedbank.api.WithdrawalPayload
import com.terraformation.backend.seedbank.db.AccessionStore
import com.terraformation.backend.seedbank.db.BagStore
import com.terraformation.backend.seedbank.db.GeolocationStore
import com.terraformation.backend.seedbank.db.ViabilityTestStore
import com.terraformation.backend.seedbank.db.WithdrawalStore
import com.terraformation.backend.seedbank.model.AccessionModel
import com.terraformation.backend.seedbank.seeds
import com.terraformation.backend.species.SpeciesService
import com.terraformation.backend.species.db.SpeciesChecker
import com.terraformation.backend.species.db.SpeciesStore
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.jooq.Record
import org.jooq.Table
import org.junit.jupiter.api.BeforeEach

internal abstract class AccessionStoreTest : DatabaseTest(), RunsAsUser {
  override val user: IndividualUser = mockUser()

  override val tablesToResetSequences: List<Table<out Record>>
    get() =
        listOf(
            ACCESSION_QUANTITY_HISTORY,
            ACCESSIONS,
            BAGS,
            GEOLOCATIONS,
            VIABILITY_TESTS,
            SPECIES,
            WITHDRAWALS)

  protected val clock: Clock = mockk()

  protected lateinit var store: AccessionStore
  protected lateinit var parentStore: ParentStore

  @BeforeEach
  protected fun init() {
    parentStore = ParentStore(dslContext)

    val speciesStore = SpeciesStore(clock, dslContext, speciesDao, speciesProblemsDao)
    val speciesChecker: SpeciesChecker = mockk()

    every { clock.instant() } returns Instant.EPOCH
    every { clock.zone } returns ZoneOffset.UTC

    every { speciesChecker.checkSpecies(any()) } just Runs
    every { speciesChecker.recheckSpecies(any(), any()) } just Runs

    every { user.canCreateAccession(any()) } returns true
    every { user.canCreateSpecies(organizationId) } returns true
    every { user.canDeleteAccession(any()) } returns true
    every { user.canDeleteSpecies(any()) } returns true
    every { user.canReadAccession(any()) } returns true
    every { user.canReadFacility(any()) } returns true
    every { user.canReadOrganization(any()) } returns true
    every { user.canReadOrganizationUser(organizationId, any()) } returns true
    every { user.canReadSpecies(any()) } returns true
    every { user.canSetWithdrawalUser(any()) } returns true
    every { user.canUpdateAccession(any()) } returns true
    every { user.canUpdateSpecies(any()) } returns true

    val messages = Messages()

    store =
        AccessionStore(
            dslContext,
            BagStore(dslContext),
            GeolocationStore(dslContext, clock),
            ViabilityTestStore(dslContext),
            parentStore,
            SpeciesService(dslContext, speciesChecker, speciesStore),
            WithdrawalStore(dslContext, clock, messages, parentStore),
            clock,
            messages,
            IdentifierGenerator(clock, dslContext),
        )

    insertSiteData()
  }

  protected fun createAccessionWithViabilityTest(): AccessionModel {
    return createAndUpdate {
      it.copy(
          processingMethod = ProcessingMethod.Count,
          initialQuantity = seeds(10),
          viabilityTests =
              listOf(
                  ViabilityTestPayload(
                      testType = ViabilityTestTypeV1.Lab,
                      startDate = LocalDate.of(2021, 4, 1),
                      seedsSown = 5)))
    }
  }

  protected fun createAndUpdate(
      edit: (UpdateAccessionRequestPayload) -> UpdateAccessionRequestPayload
  ): AccessionModel {
    val initial = store.create(AccessionModel(facilityId = facilityId))
    val edited = edit(initial.toUpdatePayload())
    return store.updateAndFetch(edited.toModel(initial.id!!))
  }

  protected fun AccessionModel.toUpdatePayload(): UpdateAccessionRequestPayload {
    return UpdateAccessionRequestPayload(
        bagNumbers = bagNumbers,
        collectedDate = collectedDate,
        collectionSiteCity = collectionSiteCity,
        collectionSiteCountryCode = collectionSiteCountryCode,
        collectionSiteCountrySubdivision = collectionSiteCountrySubdivision,
        collectionSiteLandowner = collectionSiteLandowner,
        collectionSiteName = collectionSiteName,
        collectionSiteNotes = collectionSiteNotes,
        cutTestSeedsCompromised = cutTestSeedsCompromised,
        cutTestSeedsEmpty = cutTestSeedsEmpty,
        cutTestSeedsFilled = cutTestSeedsFilled,
        dryingEndDate = dryingEndDate,
        dryingMoveDate = dryingMoveDate,
        dryingStartDate = dryingStartDate,
        environmentalNotes = collectionSiteNotes,
        facilityId = facilityId,
        fieldNotes = fieldNotes,
        founderId = founderId,
        geolocations = geolocations,
        initialQuantity = total?.let { SeedQuantityPayload(it) },
        landowner = collectionSiteLandowner,
        numberOfTrees = numberOfTrees,
        nurseryStartDate = nurseryStartDate,
        processingMethod = processingMethod,
        processingNotes = processingNotes,
        processingStaffResponsible = processingStaffResponsible,
        processingStartDate = processingStartDate,
        receivedDate = receivedDate,
        siteLocation = collectionSiteName,
        sourcePlantOrigin = sourcePlantOrigin,
        species = species,
        storageLocation = storageLocation,
        storagePackets = storagePackets,
        storageNotes = storageNotes,
        storageStaffResponsible = storageStaffResponsible,
        storageStartDate = storageStartDate,
        subsetCount = subsetCount,
        subsetWeight = subsetWeightQuantity?.let { SeedQuantityPayload(it) },
        targetStorageCondition = targetStorageCondition,
        viabilityTests = viabilityTests.map { ViabilityTestPayload(it) },
        withdrawals = withdrawals.map { WithdrawalPayload(it) },
    )
  }
}
