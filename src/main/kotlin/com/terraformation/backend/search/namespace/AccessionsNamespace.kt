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
import com.terraformation.backend.db.tables.references.GERMINATIONS
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
            bags.textField("bagNumber", "Bag number", BAGS.BAG_NUMBER),
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
            families.textField("family", "Family", FAMILIES.NAME),
            GeolocationsNamespace.GeolocationField(
                "geolocation",
                "Geolocation",
                GEOLOCATIONS.LATITUDE,
                GEOLOCATIONS.LONGITUDE,
                geolocations),
            germinationTests.dateField(
                "germinationEndDate", "Germination end date", GERMINATION_TESTS.END_DATE),
            germinationTests.integerField(
                "germinationPercentGerminated",
                "% Viability",
                GERMINATION_TESTS.TOTAL_PERCENT_GERMINATED),
            germinationTests.enumField(
                "germinationSeedType", "Seed type", GERMINATION_TESTS.SEED_TYPE_ID),
            germinations.integerField(
                "germinationSeedsGerminated",
                "Number of seeds germinated",
                GERMINATIONS.SEEDS_GERMINATED),
            germinationTests.integerField(
                "germinationSeedsSown", "Number of seeds sown", GERMINATION_TESTS.SEEDS_SOWN),
            germinationTests.dateField(
                "germinationStartDate", "Germination start date", GERMINATION_TESTS.START_DATE),
            germinationTests.enumField(
                "germinationSubstrate", "Germination substrate", GERMINATION_TESTS.SUBSTRATE_ID),
            germinationTests.textField(
                "germinationTestNotes", "Notes (germination test)", GERMINATION_TESTS.NOTES),
            germinationTests.enumField(
                "germinationTestType", "Germination test type", GERMINATION_TESTS.TEST_TYPE),
            germinationTests.enumField(
                "germinationTreatment", "Germination treatment", GERMINATION_TESTS.TREATMENT_ID),
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
            collectors.textField("primaryCollector", "Primary collector", COLLECTORS.NAME),
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
            species.textField("species", "Species", SPECIES.NAME),
            accessions.enumField("state", "State", ACCESSIONS.STATE_ID, nullable = false),
            accessions.enumField(
                "storageCondition", "Storage condition", ACCESSIONS.TARGET_STORAGE_CONDITION),
            storageLocations.textField(
                "storageLocation", "Storage location", STORAGE_LOCATIONS.NAME),
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
            accessionGerminationTestTypes.enumField(
                "viabilityTestType",
                "Viability test type (accession)",
                ACCESSION_GERMINATION_TEST_TYPES.GERMINATION_TEST_TYPE_ID),
            withdrawals.dateField("withdrawalDate", "Date of withdrawal", WITHDRAWALS.DATE),
            withdrawals.textField("withdrawalDestination", "Destination", WITHDRAWALS.DESTINATION),
            withdrawals.gramsField(
                "withdrawalGrams", "Weight of seeds withdrawn (g)", WITHDRAWALS.WITHDRAWN_GRAMS),
            withdrawals.textField("withdrawalNotes", "Notes (withdrawal)", WITHDRAWALS.NOTES),
            withdrawals.enumField("withdrawalPurpose", "Purpose", WITHDRAWALS.PURPOSE_ID),
            withdrawals.gramsField(
                "withdrawalRemainingGrams",
                "Weight in grams of seeds remaining (withdrawal)",
                WITHDRAWALS.REMAINING_GRAMS),
            withdrawals.bigDecimalField(
                "withdrawalRemainingQuantity",
                "Weight or count of seeds remaining (withdrawal)",
                WITHDRAWALS.REMAINING_QUANTITY),
            withdrawals.enumField(
                "withdrawalRemainingUnits",
                "Units of measurement of quantity remaining (withdrawal)",
                WITHDRAWALS.REMAINING_UNITS_ID),
            withdrawals.bigDecimalField(
                "withdrawalQuantity",
                "Quantity of seeds withdrawn",
                WITHDRAWALS.WITHDRAWN_QUANTITY),
            withdrawals.enumField(
                "withdrawalUnits",
                "Units of measurement of quantity withdrawn",
                WITHDRAWALS.WITHDRAWN_UNITS_ID),
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
