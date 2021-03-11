package com.terraformation.seedbank.search

import com.fasterxml.jackson.annotation.JsonValue
import com.terraformation.seedbank.db.AccessionState
import com.terraformation.seedbank.db.EnumFromReferenceTable
import com.terraformation.seedbank.db.FuzzySearchOperators
import com.terraformation.seedbank.db.UsesFuzzySearchOperators
import com.terraformation.seedbank.db.tables.references.ACCESSION
import com.terraformation.seedbank.db.tables.references.BAG
import com.terraformation.seedbank.db.tables.references.COLLECTOR
import com.terraformation.seedbank.db.tables.references.GEOLOCATION
import com.terraformation.seedbank.db.tables.references.GERMINATION
import com.terraformation.seedbank.db.tables.references.GERMINATION_TEST
import com.terraformation.seedbank.db.tables.references.SPECIES
import com.terraformation.seedbank.db.tables.references.SPECIES_FAMILY
import com.terraformation.seedbank.db.tables.references.STORAGE_LOCATION
import com.terraformation.seedbank.db.tables.references.WITHDRAWAL
import com.terraformation.seedbank.model.AccessionActive
import com.terraformation.seedbank.model.toActiveEnum
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.EnumSet
import javax.annotation.ManagedBean
import org.jooq.Condition
import org.jooq.Field
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

interface SearchField<T> {
  @get:JsonValue val fieldName: String
  val displayName: String
  val table: SearchTable
  val selectFields: List<Field<*>>
  val orderByFields: List<Field<*>>
    get() = selectFields
  val supportedFilterTypes: Set<SearchFilterType>
    get() = EnumSet.allOf(SearchFilterType::class.java)

  /** The values that this field could have, or null if it doesn't have a fixed set of options. */
  val possibleValues: List<String>?
    get() = null

  /** If true, the field is allowed to not have a value. */
  val nullable: Boolean
    get() = true

  fun getConditions(filter: SearchFilter): List<Condition>

  fun computeValue(record: Record): String?
}

@ManagedBean
class SearchFields(override val fuzzySearchOperators: FuzzySearchOperators) :
    UsesFuzzySearchOperators {
  private val fields: List<SearchField<*>> by lazy { createFieldList() }
  private val fieldsByName: Map<String, SearchField<*>> by lazy {
    fields.associateBy { it.fieldName }
  }

  @Suppress("unused")
  val fieldNames: Set<String>
    get() = fieldsByName.keys

  private fun createFieldList(): List<SearchField<*>> {
    return listOf(
        UpperCaseTextField("accessionNumber", "Accession", ACCESSION.NUMBER, nullable = false),
        ActiveField("active", "Active"),
        TextField("bagNumber", "Bag number", BAG.BAG_NUMBER, SearchTables.Bag),
        DateField("collectedDate", "Collected on", ACCESSION.COLLECTED_DATE),
        TextField("collectionNotes", "Notes (collection)", ACCESSION.ENVIRONMENTAL_NOTES),
        IntegerField(
            "cutTestSeedsCompromised",
            "Number of seeds compromised",
            ACCESSION.CUT_TEST_SEEDS_COMPROMISED),
        IntegerField("cutTestSeedsEmpty", "Number of seeds empty", ACCESSION.CUT_TEST_SEEDS_EMPTY),
        IntegerField(
            "cutTestSeedsFilled", "Number of seeds filled", ACCESSION.CUT_TEST_SEEDS_FILLED),
        DateField("dryingEndDate", "Drying end date", ACCESSION.DRYING_END_DATE),
        DateField("dryingMoveDate", "Drying move date", ACCESSION.DRYING_MOVE_DATE),
        DateField("dryingStartDate", "Drying start date", ACCESSION.DRYING_START_DATE),
        IntegerField("effectiveSeedCount", "Effective seed count", ACCESSION.EFFECTIVE_SEED_COUNT),
        BooleanField("endangered", "Endangered", ACCESSION.SPECIES_ENDANGERED),
        IntegerField(
            "estimatedSeedsIncoming", "Estimated seeds incoming", ACCESSION.EST_SEED_COUNT),
        TextField("family", "Family", SPECIES_FAMILY.NAME, SearchTables.SpeciesFamily),
        GeolocationField(
            "geolocation",
            "Geolocation",
            GEOLOCATION.LATITUDE,
            GEOLOCATION.LONGITUDE,
            SearchTables.Geolocation),
        IntegerField(
            "germinationPercentGerminated",
            "Total % of seeds germinated",
            GERMINATION_TEST.TOTAL_PERCENT_GERMINATED,
            SearchTables.GerminationTest),
        EnumField.create(
            "germinationSeedType",
            "Seed type",
            GERMINATION_TEST.SEED_TYPE_ID,
            SearchTables.GerminationTest),
        IntegerField(
            "germinationSeedsGerminated",
            "Number of seeds germinated",
            GERMINATION.SEEDS_GERMINATED,
            SearchTables.Germination),
        IntegerField(
            "germinationSeedsSown",
            "Number of seeds sown",
            GERMINATION_TEST.SEEDS_SOWN,
            SearchTables.GerminationTest),
        DateField(
            "germinationStartDate",
            "Germination start date",
            GERMINATION_TEST.START_DATE,
            SearchTables.GerminationTest),
        EnumField.create(
            "germinationSubstrate",
            "Germination substrate",
            GERMINATION_TEST.SUBSTRATE_ID,
            SearchTables.GerminationTest),
        TextField(
            "germinationTestNotes",
            "Notes (germination test)",
            GERMINATION_TEST.NOTES,
            SearchTables.GerminationTest),
        EnumField.create(
            "germinationTestType",
            "Germination test type",
            GERMINATION_TEST.TEST_TYPE,
            SearchTables.GerminationTest),
        EnumField.create(
            "germinationTreatment",
            "Germination treatment",
            GERMINATION_TEST.TREATMENT_ID,
            SearchTables.GerminationTest),
        TextField("landowner", "Landowner", ACCESSION.COLLECTION_SITE_LANDOWNER),
        DateField(
            "latestGerminationTestDate",
            "Most recent germination test date",
            ACCESSION.LATEST_GERMINATION_RECORDING_DATE),
        IntegerField(
            "latestViabilityPercent",
            "Most recent % viability",
            ACCESSION.LATEST_VIABILITY_PERCENT),
        TextField(
            "primaryCollector", "Primary collector", COLLECTOR.NAME, SearchTables.PrimaryCollector),
        EnumField.create("processingMethod", "Processing method", ACCESSION.PROCESSING_METHOD_ID),
        TextField("processingNotes", "Notes (processing)", ACCESSION.PROCESSING_NOTES),
        DateField("processingStartDate", "Processing start date", ACCESSION.PROCESSING_START_DATE),
        BooleanField("rare", "Rare", ACCESSION.SPECIES_RARE),
        DateField("receivedDate", "Received on", ACCESSION.RECEIVED_DATE),
        IntegerField("seedsCounted", "Number of seeds counted", ACCESSION.SEEDS_COUNTED),
        IntegerField("seedsRemaining", "Number of seeds remaining", ACCESSION.SEEDS_REMAINING),
        TextField("siteLocation", "Site location", ACCESSION.COLLECTION_SITE_NAME),
        TextField("species", "Species", SPECIES.NAME, SearchTables.Species),
        EnumField.create("state", "State", ACCESSION.STATE_ID, nullable = false),
        EnumField.create(
            "storageCondition", "Storage condition", ACCESSION.TARGET_STORAGE_CONDITION),
        TextField(
            "storageLocation",
            "Storage location",
            STORAGE_LOCATION.NAME,
            SearchTables.StorageLocation),
        TextField("storageNotes", "Notes (storage)", ACCESSION.STORAGE_NOTES),
        IntegerField("storagePackets", "Number of storage packets", ACCESSION.STORAGE_PACKETS),
        DateField("storageStartDate", "Storing start date", ACCESSION.STORAGE_START_DATE),
        EnumField.create(
            "targetStorageCondition", "Target %RH", ACCESSION.TARGET_STORAGE_CONDITION),
        IntegerField(
            "totalViabilityPercent",
            "Total estimated % viability",
            ACCESSION.TOTAL_VIABILITY_PERCENT),
        IntegerField(
            "treesCollectedFrom", "Number of trees collected from", ACCESSION.TREES_COLLECTED_FROM),
        DateField("withdrawalDate", "Date of withdrawal", WITHDRAWAL.DATE, SearchTables.Withdrawal),
        TextField(
            "withdrawalDestination",
            "Destination",
            WITHDRAWAL.DESTINATION,
            SearchTables.Withdrawal),
        BigDecimalField(
            "withdrawalGrams",
            "Weight of seeds withdrawn (g)",
            WITHDRAWAL.GRAMS_WITHDRAWN,
            SearchTables.Withdrawal),
        TextField(
            "withdrawalNotes", "Notes (withdrawal)", WITHDRAWAL.NOTES, SearchTables.Withdrawal),
        EnumField.create(
            "withdrawalPurpose", "Purpose", WITHDRAWAL.PURPOSE_ID, SearchTables.Withdrawal),
        IntegerField(
            "withdrawalSeeds",
            "Number of seeds withdrawn",
            WITHDRAWAL.SEEDS_WITHDRAWN,
            SearchTables.Withdrawal),
    )
  }

  operator fun get(fieldName: String) = fieldsByName[fieldName]

  abstract class SingleColumnSearchField<T : Any> : SearchField<T> {
    abstract val databaseField: Field<T?>

    abstract fun getCondition(filter: SearchFilter): Condition?

    override val selectFields: List<Field<*>>
      get() = listOf(databaseField)

    override fun getConditions(filter: SearchFilter) = listOfNotNull(getCondition(filter))

    override fun computeValue(record: Record) = record.get(databaseField)?.toString()

    override fun toString() = fieldName

    override fun hashCode() = fieldName.hashCode()

    override fun equals(other: Any?): Boolean {
      return other != null &&
          other is SingleColumnSearchField<*> &&
          other.javaClass == javaClass &&
          other.fieldName == fieldName &&
          other.databaseField == databaseField &&
          other.table == table
    }
  }

  class ActiveField(override val fieldName: String, override val displayName: String) :
      SearchField<AccessionActive> {
    override val table
      get() = SearchTables.Accession
    override val selectFields
      get() = listOf(ACCESSION.STATE_ID)
    override val possibleValues = AccessionActive::class.java.enumConstants!!.map { "$it" }
    override val nullable
      get() = false

    override fun getConditions(filter: SearchFilter): List<Condition> {
      val values = filter.values.filterNotNull().map { AccessionActive.valueOf(it) }.toSet()

      // Asking for all possible values or none at all? Filter is a no-op.
      return if (values.isEmpty() || values.size == AccessionActive.values().size) {
        emptyList()
      } else {
        // Filter for all the states that map to a requested active value.
        val states = AccessionState.values().filter { it.toActiveEnum() in values }
        listOf(ACCESSION.STATE_ID.`in`(states))
      }
    }

    override fun computeValue(record: Record): String? {
      return record[ACCESSION.STATE_ID]?.toActiveEnum()?.toString()
    }

    override val orderByFields: List<Field<*>>
      get() =
          listOf(
              DSL.case_(ACCESSION.STATE_ID)
                  .mapValues(AccessionState.values().associateWith { "${it?.toActiveEnum()}" }))

    override fun toString() = fieldName
    override fun hashCode() = fieldName.hashCode()
    override fun equals(other: Any?) = other is ActiveField && other.fieldName == fieldName
  }

  class BigDecimalField(
      override val fieldName: String,
      override val displayName: String,
      override val databaseField: TableField<*, BigDecimal?>,
      override val table: SearchTable = SearchTables.Accession
  ) : SingleColumnSearchField<BigDecimal>() {
    override val supportedFilterTypes: Set<SearchFilterType>
      get() = EnumSet.of(SearchFilterType.Exact, SearchFilterType.Range)

    override fun getCondition(filter: SearchFilter): Condition {
      val bigDecimalValues = filter.values.filterNotNull().map { BigDecimal(it) }
      return when (filter.type) {
        SearchFilterType.Exact -> {
          DSL.or(
              listOfNotNull(
                  if (bigDecimalValues.isNotEmpty()) databaseField.`in`(bigDecimalValues) else null,
                  if (filter.values.any { it == null }) databaseField.isNull else null))
        }
        SearchFilterType.Fuzzy ->
            throw RuntimeException("Fuzzy search not supported for numeric fields")
        SearchFilterType.Range ->
            if (filter.values.size == 2 && filter.values.none { it == null }) {
              databaseField.between(BigDecimal(filter.values[0]), BigDecimal(filter.values[1]))
            } else {
              throw IllegalArgumentException("Range search must have two non-null values")
            }
      }
    }

    override fun computeValue(record: Record) = record[databaseField]?.toPlainString()
  }

  class BooleanField(
      override val fieldName: String,
      override val displayName: String,
      override val databaseField: TableField<*, Boolean?>,
      override val table: SearchTable = SearchTables.Accession,
      override val nullable: Boolean = true
  ) : SingleColumnSearchField<Boolean>() {
    override val supportedFilterTypes: Set<SearchFilterType>
      get() = EnumSet.of(SearchFilterType.Exact)
    override val possibleValues = listOf("true", "false")

    override fun getCondition(filter: SearchFilter): Condition {
      if (filter.type != SearchFilterType.Exact) {
        throw IllegalArgumentException("Only exact search is supported for boolean fields")
      }

      return DSL.or(
          listOfNotNull(
              if ("true" in filter.values) databaseField.isTrue else null,
              if ("false" in filter.values) databaseField.isFalse else null,
              if (null in filter.values) databaseField.isNull else null))
    }
  }

  class DateField(
      override val fieldName: String,
      override val displayName: String,
      override val databaseField: TableField<*, LocalDate?>,
      override val table: SearchTable = SearchTables.Accession,
      override val nullable: Boolean = true
  ) : SingleColumnSearchField<LocalDate>() {
    override val supportedFilterTypes: Set<SearchFilterType>
      get() = EnumSet.of(SearchFilterType.Exact, SearchFilterType.Range)

    override fun getCondition(filter: SearchFilter): Condition {
      val dateValues =
          try {
            filter.values.filterNotNull().map { LocalDate.parse(it) }
          } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("Dates must be in YYYY-MM-DD format")
          }

      return when (filter.type) {
        SearchFilterType.Exact ->
            DSL.or(
                listOfNotNull(
                    if (dateValues.isNotEmpty()) databaseField.`in`(dateValues) else null,
                    if (filter.values.any { it == null }) databaseField.isNull else null,
                ))
        SearchFilterType.Fuzzy ->
            throw IllegalArgumentException("Fuzzy search not supported for dates")
        SearchFilterType.Range ->
            if (dateValues.size == 2) {
              databaseField.between(dateValues[0], dateValues[1])
            } else {
              throw IllegalArgumentException("Range search must have two non-null values")
            }
      }
    }
  }

  class EnumField<E : Enum<E>, T : EnumFromReferenceTable<E>>(
      override val fieldName: String,
      override val displayName: String,
      override val databaseField: TableField<*, T?>,
      override val table: SearchTable = SearchTables.Accession,
      private val enumClass: Class<T>,
      override val nullable: Boolean = true
  ) : SingleColumnSearchField<T>() {
    private val byDisplayName: Map<String, T> =
        enumClass.enumConstants!!.associateBy { it.displayName }

    override val supportedFilterTypes: Set<SearchFilterType>
      get() = EnumSet.of(SearchFilterType.Exact)
    override val possibleValues = enumClass.enumConstants!!.map { it.displayName }

    override fun getCondition(filter: SearchFilter): Condition {
      if (filter.type != SearchFilterType.Exact) {
        throw IllegalArgumentException("$fieldName only supports exact searches")
      }

      val enumInstances =
          filter.values.filterNotNull().map {
            byDisplayName[it]
                ?: throw IllegalArgumentException("Value $it not recognized for $fieldName")
          }

      return DSL.or(
          listOfNotNull(
              if (enumInstances.isNotEmpty()) databaseField.`in`(enumInstances) else null,
              if (filter.values.any { it == null }) databaseField.isNull else null))
    }

    override val orderByFields: List<Field<*>>
      get() {
        val displayNames = enumClass.enumConstants!!.map { it to it.displayName }.toMap()
        return listOf(DSL.case_(databaseField).mapValues(displayNames))
      }

    override fun computeValue(record: Record) = record[databaseField]?.displayName

    companion object {
      inline fun <E : Enum<E>, reified T : EnumFromReferenceTable<E>> create(
          fieldName: String,
          displayName: String,
          databaseField: TableField<*, T?>,
          table: SearchTable = SearchTables.Accession,
          nullable: Boolean = true
      ) = EnumField(fieldName, displayName, databaseField, table, T::class.java, nullable)
    }
  }

  class GeolocationField(
      override val fieldName: String,
      override val displayName: String,
      private val latitudeField: TableField<*, BigDecimal?>,
      private val longitudeField: TableField<*, BigDecimal?>,
      override val table: SearchTable = SearchTables.Accession,
      override val nullable: Boolean = true
  ) : SearchField<String> {
    override val supportedFilterTypes: Set<SearchFilterType>
      get() = emptySet()

    override val selectFields: List<Field<*>>
      get() = listOf(latitudeField, longitudeField)

    override fun getConditions(filter: SearchFilter): List<Condition> {
      throw IllegalArgumentException("Filters not supported for geolocation")
    }

    override fun computeValue(record: Record): String? {
      return record[latitudeField]?.let { latitude ->
        record[longitudeField]?.let { longitude ->
          "${latitude.toPlainString()}, ${longitude.toPlainString()}"
        }
      }
    }

    override fun toString() = fieldName
  }

  class IntegerField(
      override val fieldName: String,
      override val displayName: String,
      override val databaseField: TableField<*, Int?>,
      override val table: SearchTable = SearchTables.Accession,
      override val nullable: Boolean = true
  ) : SingleColumnSearchField<Int>() {
    override val supportedFilterTypes: Set<SearchFilterType>
      get() = EnumSet.of(SearchFilterType.Exact, SearchFilterType.Range)

    override fun getCondition(filter: SearchFilter): Condition {
      val intValues = filter.values.filterNotNull().map { it.toInt() }
      return when (filter.type) {
        SearchFilterType.Exact ->
            DSL.or(
                listOfNotNull(
                    if (intValues.isNotEmpty()) databaseField.`in`(intValues) else null,
                    if (filter.values.any { it == null }) databaseField.isNull else null,
                ))
        SearchFilterType.Fuzzy ->
            throw RuntimeException("Fuzzy search not supported for numeric fields")
        SearchFilterType.Range ->
            if (intValues.size == 2 && filter.values.none { it == null }) {
              databaseField.between(intValues[0], intValues[1])
            } else {
              throw IllegalArgumentException("Range search must have two non-null values")
            }
      }
    }
  }

  inner class TextField(
      override val fieldName: String,
      override val displayName: String,
      override val databaseField: Field<String?>,
      override val table: SearchTable = SearchTables.Accession,
      override val nullable: Boolean = true
  ) : SingleColumnSearchField<String>() {
    override val supportedFilterTypes: Set<SearchFilterType>
      get() = EnumSet.of(SearchFilterType.Exact, SearchFilterType.Fuzzy)

    override fun getCondition(filter: SearchFilter): Condition {
      val nonNullValues = filter.values.filterNotNull()
      return when (filter.type) {
        SearchFilterType.Exact ->
            DSL.or(
                listOfNotNull(
                    if (nonNullValues.isNotEmpty()) databaseField.`in`(nonNullValues) else null,
                    if (filter.values.any { it == null }) databaseField.isNull else null))
        SearchFilterType.Fuzzy ->
            DSL.or(
                filter.values.flatMap { value ->
                  if (value != null) {
                    listOf(databaseField.likeFuzzy(value), databaseField.like("$value%"))
                  } else {
                    listOf(databaseField.isNull)
                  }
                })
        SearchFilterType.Range ->
            throw IllegalArgumentException("Range search not supported for text fields")
      }
    }
  }

  /** Case-insensitive search for fields whose values are always upper case. */
  inner class UpperCaseTextField(
      override val fieldName: String,
      override val displayName: String,
      override val databaseField: Field<String?>,
      override val table: SearchTable = SearchTables.Accession,
      override val nullable: Boolean = true
  ) : SingleColumnSearchField<String>() {
    override val supportedFilterTypes: Set<SearchFilterType>
      get() = EnumSet.of(SearchFilterType.Exact, SearchFilterType.Fuzzy)

    override fun getCondition(filter: SearchFilter): Condition {
      val values = filter.values.mapNotNull { it?.toUpperCase() }
      return when (filter.type) {
        SearchFilterType.Exact ->
            DSL.or(
                listOfNotNull(
                    if (values.isNotEmpty()) databaseField.`in`(values) else null,
                    if (filter.values.any { it == null }) databaseField.isNull else null))
        SearchFilterType.Fuzzy ->
            DSL.or(
                filter.values.map { it?.toUpperCase() }.flatMap { value ->
                  if (value != null) {
                    listOf(databaseField.likeFuzzy(value), databaseField.like("$value%"))
                  } else {
                    listOf(databaseField.isNull)
                  }
                })
        SearchFilterType.Range ->
            throw IllegalArgumentException("Range search not supported for text fields")
      }
    }
  }
}
