package com.terraformation.backend.seedbank.event

import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.CollectionSource
import com.terraformation.backend.db.seedbank.DataSource
import com.terraformation.backend.eventlog.EntityCreatedPersistentEvent
import com.terraformation.backend.eventlog.FieldsUpdatedPersistentEvent
import com.terraformation.backend.eventlog.PersistentEvent
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.seedbank.model.Geolocation
import com.terraformation.backend.seedbank.model.SeedQuantityModel
import java.time.LocalDate

sealed interface AccessionPersistentEvent : PersistentEvent {
  val accessionId: AccessionId
  val facilityId: FacilityId
  val organizationId: OrganizationId
}

/** Published when an accession is created manually via the web app or the seed collector app. */
data class AccessionCreatedEventV1(
    val accessionNumber: String,
    val dataSource: DataSource,
    override val accessionId: AccessionId,
    override val facilityId: FacilityId,
    override val organizationId: OrganizationId,
) : EntityCreatedPersistentEvent, AccessionPersistentEvent

typealias AccessionCreatedEvent = AccessionCreatedEventV1

/** Published when an accession is created as part of a CSV import. */
data class AccessionUploadedEventV1(
    val accessionNumber: String,
    override val accessionId: AccessionId,
    override val facilityId: FacilityId,
    override val organizationId: OrganizationId,
) : EntityCreatedPersistentEvent, AccessionPersistentEvent

typealias AccessionUploadedEvent = AccessionUploadedEventV1

/**
 * Published when the user edits an accession's details. State, quantity, and child collections
 * (withdrawals, viability tests) have their own events and are not covered here.
 */
data class AccessionUpdatedEventV1(
    val changedFrom: Values,
    val changedTo: Values,
    override val accessionId: AccessionId,
    override val facilityId: FacilityId,
    override val organizationId: OrganizationId,
) : FieldsUpdatedPersistentEvent, AccessionPersistentEvent {
  data class Values(
      val bagNumbers: Set<String>? = null,
      val collectedDate: LocalDate? = null,
      val collectionSiteCity: String? = null,
      val collectionSiteCountryCode: String? = null,
      val collectionSiteCountrySubdivision: String? = null,
      val collectionSiteLandowner: String? = null,
      val collectionSiteName: String? = null,
      val collectionSiteNotes: String? = null,
      val collectionSource: CollectionSource? = null,
      val collectors: List<String>? = null,
      val dryingEndDate: LocalDate? = null,
      val founderId: String? = null,
      val geolocations: Set<Geolocation>? = null,
      val numberOfTrees: Int? = null,
      val processingNotes: String? = null,
      val projectId: ProjectId? = null,
      val receivedDate: LocalDate? = null,
      val speciesId: SpeciesId? = null,
      val subLocation: String? = null,
      val subsetCount: Int? = null,
      val subsetWeightQuantity: SeedQuantityModel? = null,
  )

  override fun listUpdatedFields(messages: Messages) =
      listOfNotNull(
          createUpdatedField(
              "bagNumbers",
              changedFrom.bagNumbers?.toList(),
              changedTo.bagNumbers?.toList(),
          ),
          createUpdatedField(
              "collectedDate",
              changedFrom.collectedDate?.toString(),
              changedTo.collectedDate?.toString(),
          ),
          createUpdatedField(
              "collectionSiteCity",
              changedFrom.collectionSiteCity,
              changedTo.collectionSiteCity,
          ),
          createUpdatedField(
              "collectionSiteCountryCode",
              changedFrom.collectionSiteCountryCode,
              changedTo.collectionSiteCountryCode,
          ),
          createUpdatedField(
              "collectionSiteCountrySubdivision",
              changedFrom.collectionSiteCountrySubdivision,
              changedTo.collectionSiteCountrySubdivision,
          ),
          createUpdatedField(
              "collectionSiteLandowner",
              changedFrom.collectionSiteLandowner,
              changedTo.collectionSiteLandowner,
          ),
          createUpdatedField(
              "collectionSiteName",
              changedFrom.collectionSiteName,
              changedTo.collectionSiteName,
          ),
          createUpdatedField(
              "collectionSiteNotes",
              changedFrom.collectionSiteNotes,
              changedTo.collectionSiteNotes,
          ),
          createUpdatedField(
              "collectionSource",
              changedFrom.collectionSource?.getDisplayName(currentLocale()),
              changedTo.collectionSource?.getDisplayName(currentLocale()),
          ),
          createUpdatedField("collectors", changedFrom.collectors, changedTo.collectors),
          createUpdatedField(
              "dryingEndDate",
              changedFrom.dryingEndDate?.toString(),
              changedTo.dryingEndDate?.toString(),
          ),
          createUpdatedField("founderId", changedFrom.founderId, changedTo.founderId),
          createUpdatedField(
              "geolocations",
              changedFrom.geolocations?.map { it.toString() },
              changedTo.geolocations?.map { it.toString() },
          ),
          createUpdatedField(
              "numberOfTrees",
              changedFrom.numberOfTrees?.toString(),
              changedTo.numberOfTrees?.toString(),
          ),
          createUpdatedField(
              "processingNotes",
              changedFrom.processingNotes,
              changedTo.processingNotes,
          ),
          createUpdatedField(
              "projectId",
              changedFrom.projectId?.toString(),
              changedTo.projectId?.toString(),
          ),
          createUpdatedField(
              "receivedDate",
              changedFrom.receivedDate?.toString(),
              changedTo.receivedDate?.toString(),
          ),
          createUpdatedField(
              "speciesId",
              changedFrom.speciesId?.toString(),
              changedTo.speciesId?.toString(),
          ),
          createUpdatedField("subLocation", changedFrom.subLocation, changedTo.subLocation),
          createUpdatedField(
              "subsetCount",
              changedFrom.subsetCount?.toString(),
              changedTo.subsetCount?.toString(),
          ),
          createUpdatedField(
              "subsetWeightQuantity",
              changedFrom.subsetWeightQuantity?.toString(),
              changedTo.subsetWeightQuantity?.toString(),
          ),
      )
}

typealias AccessionUpdatedEvent = AccessionUpdatedEventV1

typealias AccessionUpdatedEventValues = AccessionUpdatedEventV1.Values

/** Published when an accession's state changes, carrying the reason for the transition. */
data class AccessionStateChangedEventV1(
    val changedFrom: Values,
    val changedTo: Values,
    val reason: String,
    override val accessionId: AccessionId,
    override val facilityId: FacilityId,
    override val organizationId: OrganizationId,
) : FieldsUpdatedPersistentEvent, AccessionPersistentEvent {
  data class Values(
      val state: AccessionState? = null,
  )

  override fun listUpdatedFields(messages: Messages) =
      listOfNotNull(
          createUpdatedField(
              "state",
              changedFrom.state?.getDisplayName(currentLocale()),
              changedTo.state?.getDisplayName(currentLocale()),
          )
      )
}

typealias AccessionStateChangedEvent = AccessionStateChangedEventV1

typealias AccessionStateChangedEventValues = AccessionStateChangedEventV1.Values
