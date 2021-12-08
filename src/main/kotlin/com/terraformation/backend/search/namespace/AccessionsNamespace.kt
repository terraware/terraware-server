package com.terraformation.backend.search.namespace

import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.AccessionState
import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.ACCESSION_GERMINATION_TEST_TYPES
import com.terraformation.backend.db.tables.references.BAGS
import com.terraformation.backend.db.tables.references.COLLECTORS
import com.terraformation.backend.db.tables.references.FACILITIES
import com.terraformation.backend.db.tables.references.FAMILIES
import com.terraformation.backend.db.tables.references.GEOLOCATIONS
import com.terraformation.backend.db.tables.references.GERMINATION_TESTS
import com.terraformation.backend.db.tables.references.SPECIES
import com.terraformation.backend.db.tables.references.STORAGE_LOCATIONS
import com.terraformation.backend.db.tables.references.WITHDRAWALS
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFieldNamespace
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import com.terraformation.backend.seedbank.model.AccessionActive
import com.terraformation.backend.seedbank.model.toActiveEnum
import com.terraformation.backend.seedbank.search.SearchTables
import org.jooq.Condition
import org.jooq.Field
import org.jooq.Record
import org.jooq.impl.DSL

class AccessionsNamespace(
    val searchTables: SearchTables,
    collectorsNamespace: CollectorsNamespace,
    familiesNamespace: FamiliesNamespace,
    speciesNamespace: SpeciesNamespace,
    storageLocationsNamespace: StorageLocationsNamespace,
) : SearchFieldNamespace() {
  private val accessionGerminationTestTypesNamespace =
      AccessionGerminationTestTypesNamespace(searchTables)
  private val bagsNamespace = BagsNamespace(searchTables, this)
  private val facilitiesNamespace = FacilitiesNamespace(searchTables, this)
  private val geolocationsNamespace = GeolocationsNamespace(searchTables)
  private val germinationTestsNamespace = GerminationTestsNamespace(searchTables, this)
  private val withdrawalsNamespace = WithdrawalsNamespace(searchTables, this)

  override val sublists: List<SublistField> =
      listOf(
          accessionGerminationTestTypesNamespace.asMultiValueSublist(
              "viabilityTestTypes",
              ACCESSIONS.ID.eq(ACCESSION_GERMINATION_TEST_TYPES.ACCESSION_ID)),
          bagsNamespace.asMultiValueSublist("bags", ACCESSIONS.ID.eq(BAGS.ACCESSION_ID)),
          collectorsNamespace.asSingleValueSublist(
              "primaryCollectorInfo", ACCESSIONS.PRIMARY_COLLECTOR_ID.eq(COLLECTORS.ID)),
          facilitiesNamespace.asSingleValueSublist(
              "facility", ACCESSIONS.FACILITY_ID.eq(FACILITIES.ID)),
          familiesNamespace.asSingleValueSublist(
              "familyInfo", ACCESSIONS.FAMILY_ID.eq(FAMILIES.ID)),
          geolocationsNamespace.asMultiValueSublist(
              "geolocations", ACCESSIONS.ID.eq(GEOLOCATIONS.ACCESSION_ID)),
          germinationTestsNamespace.asMultiValueSublist(
              "germinationTests", ACCESSIONS.ID.eq(GERMINATION_TESTS.ACCESSION_ID)),
          speciesNamespace.asSingleValueSublist(
              "speciesInfo", ACCESSIONS.SPECIES_ID.eq(SPECIES.ID)),
          storageLocationsNamespace.asSingleValueSublist(
              "storageLocationInfo", ACCESSIONS.STORAGE_LOCATION_ID.eq(STORAGE_LOCATIONS.ID)),
          withdrawalsNamespace.asMultiValueSublist(
              "withdrawals", ACCESSIONS.ID.eq(WITHDRAWALS.ACCESSION_ID)),
      )

  override val fields =
      with(searchTables) {
        listOf(
            accessions.upperCaseTextField(
                "accessionNumber", "Accession", ACCESSIONS.NUMBER, nullable = false),
            ActiveField("active", "Active"),
            aliasField("bagNumber", "bags_number"),
            accessions.timestampField(
                "checkedInTime", "Checked-In Time", ACCESSIONS.CHECKED_IN_TIME),
            accessions.dateField("collectedDate", "Collected on", ACCESSIONS.COLLECTED_DATE),
            accessions.textField(
                "collectionNotes", "Notes (collection)", ACCESSIONS.ENVIRONMENTAL_NOTES),
            accessions.integerField(
                "cutTestSeedsCompromised",
                "Number of seeds compromised",
                ACCESSIONS.CUT_TEST_SEEDS_COMPROMISED),
            accessions.integerField(
                "cutTestSeedsEmpty", "Number of seeds empty", ACCESSIONS.CUT_TEST_SEEDS_EMPTY),
            accessions.integerField(
                "cutTestSeedsFilled", "Number of seeds filled", ACCESSIONS.CUT_TEST_SEEDS_FILLED),
            accessions.dateField("dryingEndDate", "Drying end date", ACCESSIONS.DRYING_END_DATE),
            accessions.dateField("dryingMoveDate", "Drying move date", ACCESSIONS.DRYING_MOVE_DATE),
            accessions.dateField(
                "dryingStartDate", "Drying start date", ACCESSIONS.DRYING_START_DATE),
            accessions.enumField("endangered", "Endangered", ACCESSIONS.SPECIES_ENDANGERED_TYPE_ID),
            accessions.integerField(
                "estimatedSeedsIncoming", "Estimated seeds incoming", ACCESSIONS.EST_SEED_COUNT),
            aliasField("family", "familyInfo_name"),
            aliasField("geolocation", "geolocations_coordinates"),
            aliasField("germinationEndDate", "germinationTests_endDate"),
            aliasField("germinationPercentGerminated", "germinationTests_percentGerminated"),
            aliasField("germinationSeedType", "germinationTests_seedType"),
            aliasField(
                "germinationSeedsGerminated", "germinationTests_germinations_seedsGerminated"),
            aliasField("germinationSeedsSown", "germinationTests_seedsSown"),
            aliasField("germinationStartDate", "germinationTests_startDate"),
            aliasField("germinationSubstrate", "germinationTests_substrate"),
            aliasField("germinationTestNotes", "germinationTests_notes"),
            aliasField("germinationTestType", "germinationTests_type"),
            aliasField("germinationTreatment", "germinationTests_treatment"),
            accessions.idWrapperField("id", "ID", ACCESSIONS.ID) { AccessionId(it) },
            accessions.textField("landowner", "Landowner", ACCESSIONS.COLLECTION_SITE_LANDOWNER),
            accessions.dateField(
                "latestGerminationTestDate",
                "Most recent germination test date",
                ACCESSIONS.LATEST_GERMINATION_RECORDING_DATE),
            accessions.integerField(
                "latestViabilityPercent",
                "Most recent % viability",
                ACCESSIONS.LATEST_VIABILITY_PERCENT),
            accessions.dateField(
                "nurseryStartDate", "Nursery start date", ACCESSIONS.NURSERY_START_DATE),
            aliasField("primaryCollector", "primaryCollectorInfo_name"),
            accessions.enumField(
                "processingMethod", "Processing method", ACCESSIONS.PROCESSING_METHOD_ID),
            accessions.textField(
                "processingNotes", "Notes (processing)", ACCESSIONS.PROCESSING_NOTES),
            accessions.dateField(
                "processingStartDate", "Processing start date", ACCESSIONS.PROCESSING_START_DATE),
            accessions.enumField("rare", "Rare", ACCESSIONS.RARE_TYPE_ID),
            accessions.dateField("receivedDate", "Received on", ACCESSIONS.RECEIVED_DATE),
            accessions.gramsField(
                "remainingGrams", "Remaining (grams)", ACCESSIONS.REMAINING_GRAMS),
            accessions.bigDecimalField(
                "remainingQuantity", "Remaining (quantity)", ACCESSIONS.REMAINING_QUANTITY),
            accessions.enumField(
                "remainingUnits", "Remaining (units)", ACCESSIONS.REMAINING_UNITS_ID),
            accessions.textField("siteLocation", "Site location", ACCESSIONS.COLLECTION_SITE_NAME),
            accessions.enumField(
                "sourcePlantOrigin", "Wild/Outplant", ACCESSIONS.SOURCE_PLANT_ORIGIN_ID),
            aliasField("species", "speciesInfo_name"),
            accessions.enumField("state", "State", ACCESSIONS.STATE_ID, nullable = false),
            accessions.enumField(
                "storageCondition", "Storage condition", ACCESSIONS.TARGET_STORAGE_CONDITION),
            aliasField("storageLocation", "storageLocationInfo_name"),
            accessions.textField("storageNotes", "Notes (storage)", ACCESSIONS.STORAGE_NOTES),
            accessions.integerField(
                "storagePackets", "Number of storage packets", ACCESSIONS.STORAGE_PACKETS),
            accessions.dateField(
                "storageStartDate", "Storing start date", ACCESSIONS.STORAGE_START_DATE),
            accessions.enumField(
                "targetStorageCondition", "Target %RH", ACCESSIONS.TARGET_STORAGE_CONDITION),
            accessions.gramsField("totalGrams", "Total size (grams)", ACCESSIONS.TOTAL_GRAMS),
            accessions.bigDecimalField(
                "totalQuantity", "Total size (quantity)", ACCESSIONS.TOTAL_QUANTITY),
            accessions.enumField("totalUnits", "Total size (units)", ACCESSIONS.TOTAL_UNITS_ID),
            accessions.integerField(
                "totalViabilityPercent",
                "Total estimated % viability",
                ACCESSIONS.TOTAL_VIABILITY_PERCENT),
            accessions.integerField(
                "treesCollectedFrom",
                "Number of trees collected from",
                ACCESSIONS.TREES_COLLECTED_FROM),
            aliasField("viabilityTestType", "viabilityTestTypes_type"),
            aliasField("withdrawalDate", "withdrawals_date"),
            aliasField("withdrawalDestination", "withdrawals_destination"),
            aliasField("withdrawalGrams", "withdrawals_grams"),
            aliasField("withdrawalNotes", "withdrawals_notes"),
            aliasField("withdrawalPurpose", "withdrawals_purpose"),
            aliasField("withdrawalQuantity", "withdrawals_quantity"),
            aliasField("withdrawalRemainingGrams", "withdrawals_remainingGrams"),
            aliasField("withdrawalRemainingQuantity", "withdrawals_remainingQuantity"),
            aliasField("withdrawalRemainingUnits", "withdrawals_remainingUnits"),
            aliasField("withdrawalUnits", "withdrawals_units"),
        )
      }

  /**
   * Implements the `active` field. This field doesn't actually exist in the database; it is derived
   * from the `state` field.
   */
  inner class ActiveField(override val fieldName: String, override val displayName: String) :
      SearchField {
    override val table
      get() = searchTables.accessions
    override val selectFields
      get() = listOf(ACCESSIONS.STATE_ID)
    override val possibleValues = AccessionActive::class.java.enumConstants!!.map { "$it" }
    override val nullable
      get() = false

    override fun getConditions(fieldNode: FieldNode): List<Condition> {
      val values = fieldNode.values.filterNotNull().map { AccessionActive.valueOf(it) }.toSet()

      // Asking for all possible values or none at all? Filter is a no-op.
      return if (values.isEmpty() || values.size == AccessionActive.values().size) {
        emptyList()
      } else {
        // Filter for all the states that map to a requested active value.
        val states = AccessionState.values().filter { it.toActiveEnum() in values }
        listOf(ACCESSIONS.STATE_ID.`in`(states))
      }
    }

    override fun computeValue(record: Record): String? {
      return record[ACCESSIONS.STATE_ID]?.toActiveEnum()?.toString()
    }

    override val orderByField: Field<*>
      get() =
          DSL.case_(ACCESSIONS.STATE_ID)
              .mapValues(AccessionState.values().associateWith { "${it?.toActiveEnum()}" })

    override fun toString() = fieldName
    override fun hashCode() = fieldName.hashCode()
    override fun equals(other: Any?) = other is ActiveField && other.fieldName == fieldName
  }
}
