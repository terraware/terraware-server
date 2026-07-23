package com.terraformation.backend.seedbank.event

import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SeedTreatment
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.seedbank.ViabilityTestSeedType
import com.terraformation.backend.db.seedbank.ViabilityTestSubstrate
import com.terraformation.backend.db.seedbank.ViabilityTestType
import com.terraformation.backend.eventlog.EntityCreatedPersistentEvent
import com.terraformation.backend.eventlog.EntityDeletedPersistentEvent
import com.terraformation.backend.eventlog.FieldsUpdatedPersistentEvent
import com.terraformation.backend.eventlog.PersistentEvent
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.i18n.currentLocale
import java.time.LocalDate

sealed interface ViabilityTestPersistentEvent : PersistentEvent {
  val viabilityTestId: ViabilityTestId
  val accessionId: AccessionId
  val facilityId: FacilityId
  val organizationId: OrganizationId
}

/** Published when a viability test is added to an accession. */
data class ViabilityTestCreatedEventV1(
    val testType: ViabilityTestType,
    val seedsTested: Int? = null,
    val substrate: ViabilityTestSubstrate? = null,
    val treatment: SeedTreatment? = null,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val seedType: ViabilityTestSeedType? = null,
    override val viabilityTestId: ViabilityTestId,
    override val accessionId: AccessionId,
    override val facilityId: FacilityId,
    override val organizationId: OrganizationId,
) : EntityCreatedPersistentEvent, ViabilityTestPersistentEvent

typealias ViabilityTestCreatedEvent = ViabilityTestCreatedEventV1

/**
 * Published when the user edits an existing viability test, including its germination recordings.
 */
data class ViabilityTestUpdatedEventV1(
    val changedFrom: Values,
    val changedTo: Values,
    override val viabilityTestId: ViabilityTestId,
    override val accessionId: AccessionId,
    override val facilityId: FacilityId,
    override val organizationId: OrganizationId,
) : FieldsUpdatedPersistentEvent, ViabilityTestPersistentEvent {
  data class Values(
      val endDate: LocalDate? = null,
      val notes: String? = null,
      val seedsCompromised: Int? = null,
      val seedsEmpty: Int? = null,
      val seedsFilled: Int? = null,
      val seedsTested: Int? = null,
      val seedType: ViabilityTestSeedType? = null,
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
    override val viabilityTestId: ViabilityTestId,
    override val accessionId: AccessionId,
    override val facilityId: FacilityId,
    override val organizationId: OrganizationId,
) : EntityDeletedPersistentEvent, ViabilityTestPersistentEvent

typealias ViabilityTestDeletedEvent = ViabilityTestDeletedEventV1
