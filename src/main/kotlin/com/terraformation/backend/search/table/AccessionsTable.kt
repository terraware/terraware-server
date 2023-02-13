package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.tables.references.COUNTRIES
import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.db.seedbank.tables.references.ACCESSION_COLLECTORS
import com.terraformation.backend.db.seedbank.tables.references.BAGS
import com.terraformation.backend.db.seedbank.tables.references.GEOLOCATIONS
import com.terraformation.backend.db.seedbank.tables.references.STORAGE_LOCATIONS
import com.terraformation.backend.db.seedbank.tables.references.VIABILITY_TESTS
import com.terraformation.backend.db.seedbank.tables.references.WITHDRAWALS
import com.terraformation.backend.i18n.currentLocale
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
import java.util.Locale
import java.util.ResourceBundle
import java.util.concurrent.ConcurrentHashMap
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
        upperCaseTextField("accessionNumber", ACCESSIONS.NUMBER, nullable = false),
        ActiveField("active"),
        ageField("ageMonths", ACCESSIONS.COLLECTED_DATE, AgeField.MonthGranularity, clock),
        ageField("ageYears", ACCESSIONS.COLLECTED_DATE, AgeField.YearGranularity, clock),
        aliasField("bagNumber", "bags_number"),
        dateField("collectedDate", ACCESSIONS.COLLECTED_DATE),
        textField("collectionSiteCity", ACCESSIONS.COLLECTION_SITE_CITY),
        textField("collectionSiteCountryCode", ACCESSIONS.COLLECTION_SITE_COUNTRY_CODE),
        textField(
            "collectionSiteCountrySubdivision", ACCESSIONS.COLLECTION_SITE_COUNTRY_SUBDIVISION),
        textField("collectionSiteLandowner", ACCESSIONS.COLLECTION_SITE_LANDOWNER),
        textField("collectionSiteName", ACCESSIONS.COLLECTION_SITE_NAME),
        textField("collectionSiteNotes", ACCESSIONS.COLLECTION_SITE_NOTES),
        enumField("collectionSource", ACCESSIONS.COLLECTION_SOURCE_ID),
        dateField("dryingEndDate", ACCESSIONS.DRYING_END_DATE),
        integerField("estimatedCount", ACCESSIONS.EST_SEED_COUNT),
        *weightFields(
            "estimatedWeight",
            ACCESSIONS.EST_WEIGHT_QUANTITY,
            ACCESSIONS.EST_WEIGHT_UNITS_ID,
            ACCESSIONS.EST_WEIGHT_GRAMS),
        // TODO: Remove this once clients no longer need it (new name is estimatedCount)
        integerField("estimatedSeedsIncoming", ACCESSIONS.EST_SEED_COUNT),
        aliasField("geolocation", "geolocations_coordinates"),
        idWrapperField("id", ACCESSIONS.ID) { AccessionId(it) },
        textField("plantId", ACCESSIONS.FOUNDER_ID),
        integerField("plantsCollectedFrom", ACCESSIONS.TREES_COLLECTED_FROM),
        textField("processingNotes", ACCESSIONS.PROCESSING_NOTES),
        dateField("receivedDate", ACCESSIONS.RECEIVED_DATE),
        *weightFields(
            "remaining",
            ACCESSIONS.REMAINING_QUANTITY,
            ACCESSIONS.REMAINING_UNITS_ID,
            ACCESSIONS.REMAINING_GRAMS),
        enumField("source", ACCESSIONS.DATA_SOURCE_ID),
        aliasField("speciesName", "species_scientificName"),
        enumField("state", ACCESSIONS.STATE_ID, nullable = false),
        aliasField("storageLocationName", "storageLocation_name"),
        integerField("totalViabilityPercent", ACCESSIONS.TOTAL_VIABILITY_PERCENT),
        integerField("totalWithdrawnCount", ACCESSIONS.TOTAL_WITHDRAWN_COUNT),
        *weightFields(
            "totalWithdrawnWeight",
            ACCESSIONS.TOTAL_WITHDRAWN_WEIGHT_QUANTITY,
            ACCESSIONS.TOTAL_WITHDRAWN_WEIGHT_UNITS_ID,
            ACCESSIONS.TOTAL_WITHDRAWN_WEIGHT_GRAMS),
        // TODO: Remove this once clients no longer need it (new name is plantsCollectedFrom)
        integerField("treesCollectedFrom", ACCESSIONS.TREES_COLLECTED_FROM),
        aliasField("withdrawalDate", "withdrawals_date"),
        aliasField("withdrawalDestination", "withdrawals_destination"),
        aliasField("withdrawalGrams", "withdrawals_grams"),
        aliasField("withdrawalNotes", "withdrawals_notes"),
        aliasField("withdrawalPurpose", "withdrawals_purpose"),
        aliasField("withdrawalQuantity", "withdrawals_quantity"),
        aliasField("withdrawalUnits", "withdrawals_units"),
    )
  }

  override fun conditionForVisibility(): Condition {
    return ACCESSIONS.FACILITY_ID.`in`(currentUser().facilityRoles.keys)
  }

  override fun conditionForScope(scope: SearchScope): Condition {
    return when (scope) {
      is OrganizationIdScope -> ACCESSIONS.facilities.ORGANIZATION_ID.eq(scope.organizationId)
      is FacilityIdScope -> ACCESSIONS.FACILITY_ID.eq(scope.facilityId)
    }
  }

  override val defaultOrderFields: List<OrderField<*>>
    get() = listOf(ACCESSIONS.NUMBER, ACCESSIONS.ID)

  /**
   * Implements the `active` field. This field doesn't actually exist in the database; it is derived
   * from the `state` field.
   */
  inner class ActiveField(override val fieldName: String, override val localize: Boolean = true) :
      SearchField {
    private val activeStrings = ConcurrentHashMap<Locale, String>()
    private val inactiveStrings = ConcurrentHashMap<Locale, String>()

    override val table: SearchTable
      get() = this@AccessionsTable
    override val selectFields
      get() = listOf(ACCESSIONS.STATE_ID)
    override val possibleValues = AccessionActive::class.java.enumConstants!!.map { "$it" }
    override val nullable
      get() = false

    override fun getConditions(fieldNode: FieldNode): List<Condition> {
      val values =
          fieldNode.values
              .filterNotNull()
              .map { stringValue ->
                when (stringValue) {
                  AccessionActive.Active.render() -> AccessionActive.Active
                  AccessionActive.Inactive.render() -> AccessionActive.Inactive
                  else -> throw IllegalArgumentException("Unrecognized value $stringValue")
                }
              }
              .toSet()

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
      return record[ACCESSIONS.STATE_ID]?.toActiveEnum()?.render()
    }

    override val orderByField: Field<*>
      get() =
          DSL.case_(ACCESSIONS.STATE_ID)
              .mapValues(
                  AccessionState.values().associateWith { "${it?.toActiveEnum()?.render()}" })

    override fun raw(): SearchField? {
      return if (localize) {
        ActiveField(rawFieldName(), false)
      } else {
        null
      }
    }

    override fun toString() = fieldName
    override fun hashCode() = fieldName.hashCode()
    override fun equals(other: Any?) = other is ActiveField && other.fieldName == fieldName

    private fun AccessionActive.render(): String {
      return if (localize) {
        val locale = currentLocale()
        val stringMap =
            when (this) {
              AccessionActive.Active -> activeStrings
              AccessionActive.Inactive -> inactiveStrings
            }

        return stringMap.getOrPut(locale) {
          ResourceBundle.getBundle("i18n.Messages", locale).getString("accessionState.$this")
        }
      } else {
        "$this"
      }
    }
  }
}
