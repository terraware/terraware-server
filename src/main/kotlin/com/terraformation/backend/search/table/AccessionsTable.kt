package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.AccessionState
import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.ACCESSION_COLLECTORS
import com.terraformation.backend.db.tables.references.BAGS
import com.terraformation.backend.db.tables.references.COUNTRIES
import com.terraformation.backend.db.tables.references.FACILITIES
import com.terraformation.backend.db.tables.references.GEOLOCATIONS
import com.terraformation.backend.db.tables.references.SPECIES
import com.terraformation.backend.db.tables.references.STORAGE_LOCATIONS
import com.terraformation.backend.db.tables.references.VIABILITY_TESTS
import com.terraformation.backend.db.tables.references.WITHDRAWALS
import com.terraformation.backend.search.FacilityIdScope
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.OrganizationIdScope
import com.terraformation.backend.search.SearchScope
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.AgeField
import com.terraformation.backend.search.field.SearchField
import com.terraformation.backend.seedbank.model.AccessionActive
import com.terraformation.backend.seedbank.model.toActiveEnum
import java.time.Clock
import org.jooq.Condition
import org.jooq.Field
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

class AccessionsTable(private val tables: SearchTables, private val clock: Clock) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = ACCESSIONS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          countries.asSingleValueSublist(
              "collectionSiteCountry",
              ACCESSIONS.COLLECTION_SITE_COUNTRY_CODE.eq(COUNTRIES.CODE),
              isRequired = false),
          accessionCollectors.asMultiValueSublist(
              "collectors", ACCESSIONS.ID.eq(ACCESSION_COLLECTORS.ACCESSION_ID)),
          // TODO: Remove this once client is updated to search the unified collectors list
          accessionCollectors.asMultiValueSublist(
              "primaryCollectors",
              ACCESSIONS.ID.eq(ACCESSION_COLLECTORS.ACCESSION_ID)
                  .and(ACCESSION_COLLECTORS.POSITION.eq(0))),
          // TODO: Remove this once client is updated to search the unified collectors list
          accessionCollectors.asMultiValueSublist(
              "secondaryCollectors",
              ACCESSIONS.ID.eq(ACCESSION_COLLECTORS.ACCESSION_ID)
                  .and(ACCESSION_COLLECTORS.POSITION.gt(0))),
          bags.asMultiValueSublist("bags", ACCESSIONS.ID.eq(BAGS.ACCESSION_ID)),
          facilities.asSingleValueSublist("facility", ACCESSIONS.FACILITY_ID.eq(FACILITIES.ID)),
          geolocations.asMultiValueSublist(
              "geolocations", ACCESSIONS.ID.eq(GEOLOCATIONS.ACCESSION_ID)),
          species.asSingleValueSublist(
              "species", ACCESSIONS.SPECIES_ID.eq(SPECIES.ID), isRequired = false),
          storageLocations.asSingleValueSublist(
              "storageLocation",
              ACCESSIONS.STORAGE_LOCATION_ID.eq(STORAGE_LOCATIONS.ID),
              isRequired = false),
          viabilityTests.asMultiValueSublist(
              "viabilityTests", ACCESSIONS.ID.eq(VIABILITY_TESTS.ACCESSION_ID)),
          withdrawals.asMultiValueSublist(
              "withdrawals", ACCESSIONS.ID.eq(WITHDRAWALS.ACCESSION_ID)),
      )
    }
  }

  // This needs to be lazy-initialized because aliasField() references the list of sublists
  override val fields: List<SearchField> by lazy {
    listOf(
        upperCaseTextField("accessionNumber", "Accession", ACCESSIONS.NUMBER, nullable = false),
        ActiveField("active", "Active"),
        ageField(
            "ageMonths",
            "Age (months)",
            ACCESSIONS.COLLECTED_DATE,
            AgeField.MonthGranularity,
            clock),
        ageField(
            "ageYears", "Age (years)", ACCESSIONS.COLLECTED_DATE, AgeField.YearGranularity, clock),
        aliasField("bagNumber", "bags_number"),
        timestampField("checkedInTime", "Checked-In Time", ACCESSIONS.CHECKED_IN_TIME),
        dateField("collectedDate", "Collected on", ACCESSIONS.COLLECTED_DATE),
        textField("collectionNotes", "Notes (collection)", ACCESSIONS.COLLECTION_SITE_NOTES),
        textField("collectionSiteCity", "Collection site city", ACCESSIONS.COLLECTION_SITE_CITY),
        textField(
            "collectionSiteCountryCode",
            "Collection site country code",
            ACCESSIONS.COLLECTION_SITE_COUNTRY_CODE),
        textField(
            "collectionSiteCountrySubdivision",
            "Collection site country subdivision",
            ACCESSIONS.COLLECTION_SITE_COUNTRY_SUBDIVISION),
        textField(
            "collectionSiteLandowner",
            "Collection site landowner",
            ACCESSIONS.COLLECTION_SITE_LANDOWNER),
        textField("collectionSiteName", "Collection site name", ACCESSIONS.COLLECTION_SITE_NAME),
        textField("collectionSiteNotes", "Collection site notes", ACCESSIONS.COLLECTION_SITE_NOTES),
        integerField(
            "cutTestSeedsCompromised",
            "Number of seeds compromised",
            ACCESSIONS.CUT_TEST_SEEDS_COMPROMISED),
        integerField("cutTestSeedsEmpty", "Number of seeds empty", ACCESSIONS.CUT_TEST_SEEDS_EMPTY),
        integerField(
            "cutTestSeedsFilled", "Number of seeds filled", ACCESSIONS.CUT_TEST_SEEDS_FILLED),
        dateField("dryingEndDate", "Drying end date", ACCESSIONS.DRYING_END_DATE),
        dateField("dryingMoveDate", "Drying move date", ACCESSIONS.DRYING_MOVE_DATE),
        dateField("dryingStartDate", "Drying start date", ACCESSIONS.DRYING_START_DATE),
        enumField("endangered", "Endangered", ACCESSIONS.SPECIES_ENDANGERED_TYPE_ID),
        integerField(
            "estimatedSeedsIncoming", "Estimated seeds incoming", ACCESSIONS.EST_SEED_COUNT),
        textField("familyName", "Family name", ACCESSIONS.FAMILY_NAME),
        aliasField("geolocation", "geolocations_coordinates"),
        idWrapperField("id", "ID", ACCESSIONS.ID) { AccessionId(it) },
        textField("landowner", "Landowner", ACCESSIONS.COLLECTION_SITE_LANDOWNER),
        integerField(
            "latestViabilityPercent",
            "Most recent % viability",
            ACCESSIONS.LATEST_VIABILITY_PERCENT),
        dateField(
            "latestViabilityTestDate",
            "Most recent viability test result date",
            ACCESSIONS.LATEST_GERMINATION_RECORDING_DATE),
        dateField("nurseryStartDate", "Nursery start date", ACCESSIONS.NURSERY_START_DATE),
        aliasField("primaryCollectorName", "primaryCollectors_name"),
        enumField("processingMethod", "Processing method", ACCESSIONS.PROCESSING_METHOD_ID),
        textField("processingNotes", "Notes (processing)", ACCESSIONS.PROCESSING_NOTES),
        dateField("processingStartDate", "Processing start date", ACCESSIONS.PROCESSING_START_DATE),
        enumField("rare", "Rare", ACCESSIONS.RARE_TYPE_ID),
        dateField("receivedDate", "Received on", ACCESSIONS.RECEIVED_DATE),
        gramsField("remainingGrams", "Remaining (grams)", ACCESSIONS.REMAINING_GRAMS),
        bigDecimalField("remainingQuantity", "Remaining (quantity)", ACCESSIONS.REMAINING_QUANTITY),
        enumField("remainingUnits", "Remaining (units)", ACCESSIONS.REMAINING_UNITS_ID),
        textField("siteLocation", "Site location", ACCESSIONS.COLLECTION_SITE_NAME),
        enumField("sourcePlantOrigin", "Wild/Outplant", ACCESSIONS.SOURCE_PLANT_ORIGIN_ID),
        aliasField("speciesName", "species_scientificName"),
        enumField("state", "State", ACCESSIONS.STATE_ID, nullable = false),
        enumField("storageCondition", "Storage condition", ACCESSIONS.TARGET_STORAGE_CONDITION),
        aliasField("storageLocationName", "storageLocation_name"),
        textField("storageNotes", "Notes (storage)", ACCESSIONS.STORAGE_NOTES),
        integerField("storagePackets", "Number of storage packets", ACCESSIONS.STORAGE_PACKETS),
        dateField("storageStartDate", "Storing start date", ACCESSIONS.STORAGE_START_DATE),
        enumField("targetStorageCondition", "Target %RH", ACCESSIONS.TARGET_STORAGE_CONDITION),
        gramsField("totalGrams", "Total size (grams)", ACCESSIONS.TOTAL_GRAMS),
        bigDecimalField("totalQuantity", "Total size (quantity)", ACCESSIONS.TOTAL_QUANTITY),
        enumField("totalUnits", "Total size (units)", ACCESSIONS.TOTAL_UNITS_ID),
        integerField(
            "totalViabilityPercent",
            "Total estimated % viability",
            ACCESSIONS.TOTAL_VIABILITY_PERCENT),
        integerField(
            "treesCollectedFrom",
            "Number of trees collected from",
            ACCESSIONS.TREES_COLLECTED_FROM),
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

  override fun conditionForVisibility(): Condition {
    return ACCESSIONS.FACILITY_ID.`in`(currentUser().facilityRoles.keys)
  }

  override fun conditionForScope(scope: SearchScope): Condition {
    return when (scope) {
      is OrganizationIdScope -> ACCESSIONS.facilities().ORGANIZATION_ID.eq(scope.organizationId)
      is FacilityIdScope -> ACCESSIONS.FACILITY_ID.eq(scope.facilityId)
    }
  }

  override val defaultOrderFields: List<OrderField<*>>
    get() = listOf(ACCESSIONS.NUMBER, ACCESSIONS.ID)

  /**
   * Implements the `active` field. This field doesn't actually exist in the database; it is derived
   * from the `state` field.
   */
  inner class ActiveField(override val fieldName: String, override val displayName: String) :
      SearchField {
    override val table: SearchTable
      get() = this@AccessionsTable
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
