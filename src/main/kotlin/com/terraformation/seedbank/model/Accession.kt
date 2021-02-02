package com.terraformation.seedbank.model

import com.terraformation.seedbank.api.seedbank.Geolocation
import com.terraformation.seedbank.db.AccessionState
import com.terraformation.seedbank.db.ProcessingMethod
import com.terraformation.seedbank.db.StorageCondition
import java.math.BigDecimal
import java.time.LocalDate

interface AccessionFields {
  val accessionNumber: String?
    get() = null
  val state: AccessionState?
    get() = null
  val status: String?
    get() = null
  val species: String?
    get() = null
  val family: String?
    get() = null
  val numberOfTrees: Int?
    get() = null
  val founderId: String?
    get() = null
  val endangered: Boolean?
    get() = null
  val rare: Boolean?
    get() = null
  val fieldNotes: String?
    get() = null
  val collectedDate: LocalDate?
    get() = null
  val receivedDate: LocalDate?
    get() = null
  val primaryCollector: String?
    get() = null
  val secondaryCollectors: Set<String>?
    get() = null
  val siteLocation: String?
    get() = null
  val landowner: String?
    get() = null
  val environmentalNotes: String?
    get() = null
  val processingStartDate: LocalDate?
    get() = null
  val processingMethod: ProcessingMethod?
    get() = null
  val seedsCounted: Int?
    get() = null
  val subsetWeightGrams: BigDecimal?
    get() = null
  val totalWeightGrams: BigDecimal?
    get() = null
  val subsetCount: Int?
    get() = null
  val estimatedSeedCount: Int?
    get() = null
  val targetStorageCondition: StorageCondition?
    get() = null
  val dryingStartDate: LocalDate?
    get() = null
  val dryingEndDate: LocalDate?
    get() = null
  val dryingMoveDate: LocalDate?
    get() = null
  val processingNotes: String?
    get() = null
  val processingStaffResponsible: String?
    get() = null
  val bagNumbers: Set<String>?
    get() = null
  val photoFilenames: Set<String>?
    get() = null
  val geolocations: Set<Geolocation>?
    get() = null
  val germinationTests: Set<GerminationTestFields>?
    get() = null
}

interface ConcreteAccession : AccessionFields {
  override val accessionNumber: String
  override val state: AccessionState
  override val status: String
}

data class AccessionModel(
    val id: Long,
    override val accessionNumber: String,
    override val state: AccessionState,
    override val status: String,
    override val species: String? = null,
    override val family: String? = null,
    override val numberOfTrees: Int? = null,
    override val founderId: String? = null,
    override val endangered: Boolean? = null,
    override val rare: Boolean? = null,
    override val fieldNotes: String? = null,
    override val collectedDate: LocalDate? = null,
    override val receivedDate: LocalDate? = null,
    override val primaryCollector: String? = null,
    override val secondaryCollectors: Set<String>? = null,
    override val siteLocation: String? = null,
    override val landowner: String? = null,
    override val environmentalNotes: String? = null,
    override val processingStartDate: LocalDate? = null,
    override val processingMethod: ProcessingMethod? = null,
    override val seedsCounted: Int? = null,
    override val subsetWeightGrams: BigDecimal? = null,
    override val totalWeightGrams: BigDecimal? = null,
    override val subsetCount: Int? = null,
    override val estimatedSeedCount: Int? = null,
    override val targetStorageCondition: StorageCondition? = null,
    override val dryingStartDate: LocalDate? = null,
    override val dryingEndDate: LocalDate? = null,
    override val dryingMoveDate: LocalDate? = null,
    override val processingNotes: String? = null,
    override val processingStaffResponsible: String? = null,
    override val bagNumbers: Set<String>? = null,
    override val photoFilenames: Set<String>? = null,
    override val geolocations: Set<Geolocation>? = null,
    override val germinationTests: Set<GerminationTestModel>? = null
) : ConcreteAccession
