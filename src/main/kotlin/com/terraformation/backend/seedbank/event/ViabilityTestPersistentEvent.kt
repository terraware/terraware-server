package com.terraformation.backend.seedbank.event

import com.terraformation.backend.db.default_schema.EventLogId
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SeedTreatment
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.seedbank.ViabilityTestSeedType
import com.terraformation.backend.db.seedbank.ViabilityTestSubstrate
import com.terraformation.backend.db.seedbank.ViabilityTestType
import com.terraformation.backend.eventlog.EntityDeletedPersistentEvent
import com.terraformation.backend.eventlog.FieldsCreatedPersistentEvent
import com.terraformation.backend.eventlog.FieldsUpdatedPersistentEvent
import com.terraformation.backend.eventlog.PersistentEvent
import com.terraformation.backend.eventlog.UpgradableEvent
import com.terraformation.backend.eventlog.db.EventUpgradeUtils
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.i18n.currentLocale
import java.time.LocalDate

sealed interface ViabilityTestPersistentEvent : PersistentEvent {
  val accessionId: AccessionId
  val facilityId: FacilityId
  val organizationId: OrganizationId
  val viabilityTestId: ViabilityTestId
}

data class ViabilityTestCreatedEventV1(
    override val accessionId: AccessionId,
    val endDate: LocalDate? = null,
    override val facilityId: FacilityId,
    override val organizationId: OrganizationId,
    val seedsTested: Int? = null,
    val seedType: ViabilityTestSeedType? = null,
    val startDate: LocalDate? = null,
    val substrate: ViabilityTestSubstrate? = null,
    val testType: ViabilityTestType,
    val treatment: SeedTreatment? = null,
    override val viabilityTestId: ViabilityTestId,
) : UpgradableEvent, ViabilityTestPersistentEvent {
  override fun toNextVersion(
      eventLogId: EventLogId,
      eventUpgradeUtils: EventUpgradeUtils,
  ): ViabilityTestCreatedEventV2 {
    val missingValues =
        eventUpgradeUtils.getViabilityTestValuesMissingFromV1(viabilityTestId, eventLogId)

    return ViabilityTestCreatedEventV2(
        accessionId = accessionId,
        endDate = endDate,
        facilityId = facilityId,
        notes = missingValues.notes,
        organizationId = organizationId,
        seedsTested = seedsTested,
        seedType = seedType,
        staffResponsible = missingValues.staffResponsible,
        startDate = startDate,
        substrate = substrate,
        testType = testType,
        treatment = treatment,
        viabilityTestId = viabilityTestId,
    )
  }
}

/** Published when a viability test is added to an accession. */
data class ViabilityTestCreatedEventV2(
    override val accessionId: AccessionId,
    val endDate: LocalDate? = null,
    override val facilityId: FacilityId,
    val notes: String? = null,
    override val organizationId: OrganizationId,
    val seedsTested: Int? = null,
    val seedType: ViabilityTestSeedType? = null,
    val staffResponsible: String? = null,
    val startDate: LocalDate? = null,
    val substrate: ViabilityTestSubstrate? = null,
    val testType: ViabilityTestType,
    val treatment: SeedTreatment? = null,
    override val viabilityTestId: ViabilityTestId,
) : FieldsCreatedPersistentEvent, ViabilityTestPersistentEvent {
  override fun listInitialFields(messages: Messages) =
      listOfNotNull(
          createInitialField("endDate", endDate?.toString()),
          createInitialField("notes", notes),
          createInitialField("seedsTested", seedsTested?.toString()),
          createInitialField("seedType", seedType?.getDisplayName(currentLocale())),
          createInitialField("staffResponsible", staffResponsible),
          createInitialField("startDate", startDate?.toString()),
          createInitialField("substrate", substrate?.getDisplayName(currentLocale())),
          createInitialField("testType", testType.getDisplayName(currentLocale())),
          createInitialField("treatment", treatment?.getDisplayName(currentLocale())),
      )
}

typealias ViabilityTestCreatedEvent = ViabilityTestCreatedEventV2

/**
 * Published when the user edits an existing viability test, including its germination recordings.
 */
data class ViabilityTestUpdatedEventV1(
    override val accessionId: AccessionId,
    val changedFrom: Values,
    val changedTo: Values,
    override val facilityId: FacilityId,
    override val organizationId: OrganizationId,
    override val viabilityTestId: ViabilityTestId,
) : FieldsUpdatedPersistentEvent, ViabilityTestPersistentEvent {
  data class Values(
      val endDate: LocalDate? = null,
      val notes: String? = null,
      val seedsCompromised: Int? = null,
      val seedsEmpty: Int? = null,
      val seedsFilled: Int? = null,
      val seedsTested: Int? = null,
      val seedType: ViabilityTestSeedType? = null,
      val staffResponsible: String? = null,
      val startDate: LocalDate? = null,
      val substrate: ViabilityTestSubstrate? = null,
      val totalSeedsGerminated: Int? = null,
      val treatment: SeedTreatment? = null,
      val viabilityPercent: Int? = null,
  )

  override fun listUpdatedFields(messages: Messages) =
      listOfNotNull(
          createUpdatedField(
              "endDate",
              changedFrom.endDate?.toString(),
              changedTo.endDate?.toString(),
          ),
          createUpdatedField("notes", changedFrom.notes, changedTo.notes),
          createUpdatedField(
              "seedsCompromised",
              changedFrom.seedsCompromised?.toString(),
              changedTo.seedsCompromised?.toString(),
          ),
          createUpdatedField(
              "seedsEmpty",
              changedFrom.seedsEmpty?.toString(),
              changedTo.seedsEmpty?.toString(),
          ),
          createUpdatedField(
              "seedsFilled",
              changedFrom.seedsFilled?.toString(),
              changedTo.seedsFilled?.toString(),
          ),
          createUpdatedField(
              "seedsTested",
              changedFrom.seedsTested?.toString(),
              changedTo.seedsTested?.toString(),
          ),
          createUpdatedField(
              "seedType",
              changedFrom.seedType?.getDisplayName(currentLocale()),
              changedTo.seedType?.getDisplayName(currentLocale()),
          ),
          createUpdatedField(
              "staffResponsible",
              changedFrom.staffResponsible,
              changedTo.staffResponsible,
          ),
          createUpdatedField(
              "startDate",
              changedFrom.startDate?.toString(),
              changedTo.startDate?.toString(),
          ),
          createUpdatedField(
              "substrate",
              changedFrom.substrate?.getDisplayName(currentLocale()),
              changedTo.substrate?.getDisplayName(currentLocale()),
          ),
          createUpdatedField(
              "totalSeedsGerminated",
              changedFrom.totalSeedsGerminated?.toString(),
              changedTo.totalSeedsGerminated?.toString(),
          ),
          createUpdatedField(
              "treatment",
              changedFrom.treatment?.getDisplayName(currentLocale()),
              changedTo.treatment?.getDisplayName(currentLocale()),
          ),
          createUpdatedField(
              "viabilityPercent",
              changedFrom.viabilityPercent?.toString(),
              changedTo.viabilityPercent?.toString(),
          ),
      )
}

typealias ViabilityTestUpdatedEvent = ViabilityTestUpdatedEventV1

typealias ViabilityTestUpdatedEventValues = ViabilityTestUpdatedEventV1.Values

/** Published when a viability test is deleted from an accession. */
data class ViabilityTestDeletedEventV1(
    override val accessionId: AccessionId,
    override val facilityId: FacilityId,
    override val organizationId: OrganizationId,
    override val viabilityTestId: ViabilityTestId,
) : EntityDeletedPersistentEvent, ViabilityTestPersistentEvent

typealias ViabilityTestDeletedEvent = ViabilityTestDeletedEventV1
