package com.terraformation.backend.seedbank.db.accessionStore

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.IdentifierGenerator
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.DataSource
import com.terraformation.backend.db.seedbank.ViabilityTestType
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.db.seedbank.tables.references.ACCESSION_QUANTITY_HISTORY
import com.terraformation.backend.db.seedbank.tables.references.BAGS
import com.terraformation.backend.db.seedbank.tables.references.GEOLOCATIONS
import com.terraformation.backend.db.seedbank.tables.references.VIABILITY_TESTS
import com.terraformation.backend.db.seedbank.tables.references.WITHDRAWALS
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.mockUser
import com.terraformation.backend.seedbank.db.AccessionStore
import com.terraformation.backend.seedbank.db.BagStore
import com.terraformation.backend.seedbank.db.GeolocationStore
import com.terraformation.backend.seedbank.db.ViabilityTestStore
import com.terraformation.backend.seedbank.db.WithdrawalStore
import com.terraformation.backend.seedbank.model.AccessionModel
import com.terraformation.backend.seedbank.model.Geolocation
import com.terraformation.backend.seedbank.model.SeedQuantityModel
import com.terraformation.backend.seedbank.model.ViabilityTestModel
import com.terraformation.backend.seedbank.model.WithdrawalModel
import com.terraformation.backend.seedbank.seeds
import io.mockk.every
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
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

  protected val clock = TestClock()

  protected lateinit var store: AccessionStore
  protected lateinit var parentStore: ParentStore

  @BeforeEach
  protected fun init() {
    parentStore = ParentStore(dslContext)

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
            WithdrawalStore(dslContext, clock, messages, parentStore),
            clock,
            messages,
            IdentifierGenerator(clock, dslContext),
        )

    insertSiteData()
  }

  protected fun createAccessionWithViabilityTest(): AccessionModel {
    return create()
        .andUpdate { it.copy(remaining = seeds(10)) }
        .andUpdate {
          it.addViabilityTest(
              ViabilityTestModel(
                  seedsTested = 5,
                  startDate = LocalDate.of(2021, 4, 1),
                  testType = ViabilityTestType.Lab))
        }
  }

  protected fun create(model: AccessionModel = accessionModel()): AccessionModel {
    return store.create(model)
  }

  /**
   * Fluent wrapper for [AccessionStore.updateAndFetch] that calls a function to edit an accession,
   * then saves the result. This allows tests to more clearly express the sequence of
   * transformations they're making to accessions.
   *
   * Typical usage:
   * ```
   * val accession = create()
   *     .andUpdate { it.copy(foo=123) }
   *     .andUpdate { it.copy(bar=456) }
   * ```
   */
  protected fun AccessionModel.andUpdate(edit: (AccessionModel) -> AccessionModel): AccessionModel {
    val edited = edit(this)
    return store.updateAndFetch(edited)
  }

  /**
   * Fluent API to advance the mock clock between updates to an accession. This allows tests to more
   * clearly express time-sensitive sequences of operations.
   *
   * Typical usage:
   * ```
   * val accession = create()
   *     .andUpdate { it.copy(remaining = seeds(10) }
   *     .andAdvanceClock(Duration.ofSeconds(30))
   *     .andUpdate { it.addWithdrawal(..., clock) }
   * ```
   *
   * @return [this]
   */
  protected fun AccessionModel.andAdvanceClock(duration: Duration): AccessionModel {
    val desiredTime = clock.instant() + duration
    clock.instant = desiredTime
    return this
  }

  /** AccessionModel constructor wrapper that supplies defaults for required properties. */
  protected fun accessionModel(
      bagNumbers: Set<String> = emptySet(),
      clock: Clock = this.clock,
      collectors: List<String> = emptyList(),
      facilityId: FacilityId = this.facilityId,
      geolocations: Set<Geolocation> = emptySet(),
      processingNotes: String? = null,
      remaining: SeedQuantityModel? = null,
      source: DataSource? = null,
      species: String? = null,
      speciesId: SpeciesId? = null,
      state: AccessionState? = null,
      withdrawals: List<WithdrawalModel> = emptyList(),
  ): AccessionModel =
      AccessionModel(
          bagNumbers = bagNumbers,
          clock = clock,
          collectors = collectors,
          facilityId = facilityId,
          geolocations = geolocations,
          processingNotes = processingNotes,
          remaining = remaining,
          source = source,
          species = species,
          speciesId = speciesId,
          state = state,
          withdrawals = withdrawals,
      )
}
