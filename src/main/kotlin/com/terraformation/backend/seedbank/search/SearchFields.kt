package com.terraformation.backend.seedbank.search

import com.fasterxml.jackson.annotation.JsonValue
import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.AccessionState
import com.terraformation.backend.db.EnumFromReferenceTable
import com.terraformation.backend.db.FuzzySearchOperators
import com.terraformation.backend.db.SeedQuantityUnits
import com.terraformation.backend.db.UsesFuzzySearchOperators
import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.ACCESSION_GERMINATION_TEST_TYPES
import com.terraformation.backend.db.tables.references.BAGS
import com.terraformation.backend.db.tables.references.COLLECTORS
import com.terraformation.backend.db.tables.references.FAMILIES
import com.terraformation.backend.db.tables.references.GEOLOCATIONS
import com.terraformation.backend.db.tables.references.GERMINATIONS
import com.terraformation.backend.db.tables.references.GERMINATION_TESTS
import com.terraformation.backend.db.tables.references.SPECIES
import com.terraformation.backend.db.tables.references.STORAGE_LOCATIONS
import com.terraformation.backend.db.tables.references.WITHDRAWALS
import com.terraformation.backend.seedbank.model.AccessionActive
import com.terraformation.backend.seedbank.model.toActiveEnum
import com.terraformation.backend.seedbank.model.toGrams
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.EnumSet
import javax.annotation.ManagedBean
import org.jetbrains.annotations.NotNull
import org.jooq.Condition
import org.jooq.Field
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

/**
 * Metadata about a field that can be included in accession search requests. This is used by
 * [SearchService] to dynamically construct SQL queries for arbitrary user-specified searches.
 */
interface SearchField {
  /**
   * The name of the field as presented in the search API. This does not necessarily exactly match
   * the column name, though in most cases it should be similar.
   */
  @get:JsonValue val fieldName: String

  /**
   * The field's human-readable name. This is used when exporting search results, where the exported
   * file needs to include field labels. This generally matches the field name in the seed bank UI,
   * though in some cases it's abbreviated here.
   */
  val displayName: String

  /**
   * Which table the field is in. [SearchService] joins with this table when constructing queries.
   */
  val table: SearchTable

  /**
   * Which database columns contain the field's data. In most cases, this will be a 1-element list,
   * but it can have multiple elements in the case of fields such as geolocation that are presented
   * as composite values in search results but stored as individual components in the database.
   */
  val selectFields: List<Field<*>>

  /**
   * Which values are used when the query results are ordered by this field. Most of the time this
   * is the same as [selectFields] and the default implementation delegates to that value, but for
   * fields with computed values, the "field" here may be an expression rather than a simple column
   * name.
   */
  val orderByFields: List<Field<*>>
    get() = selectFields

  /**
   * Which kinds of filters are allowed for this field. For example, it makes no sense to support
   * fuzzy text search on numeric values.
   */
  val supportedFilterTypes: Set<SearchFilterType>
    get() = EnumSet.allOf(SearchFilterType::class.java)

  /** The values that this field could have, or null if it doesn't have a fixed set of options. */
  val possibleValues: List<String>?
    get() = null

  /** If true, the field is allowed to not have a value. */
  val nullable: Boolean
    get() = true

  /**
   * Returns a list of conditions to include in a WHERE clause when this field is used to filter
   * search results. This may vary based on the filter type.
   */
  fun getConditions(fieldNode: FieldNode): List<Condition>

  /**
   * Renders the value of this field as a string given a row of results from a search query.
   * Typically this will just call `toString()` on the value of a single element of [record] but for
   * fields with computed values or that are composites of multiple columns, this can include
   * additional logic.
   */
  fun computeValue(record: Record): String?
}

/**
 * Contains a list of all the available [SearchField] s.
 *
 * The list is constructed at runtime rather than declared statically because some of the
 * implementations depend on Spring-managed services.
 */
@ManagedBean
class SearchFields(override val fuzzySearchOperators: FuzzySearchOperators) :
    UsesFuzzySearchOperators {
  private val fields: List<SearchField> by lazy { createFieldList() }
  private val fieldsByName: Map<String, SearchField> by lazy { fields.associateBy { it.fieldName } }

  val fieldNames: Set<String>
    get() = fieldsByName.keys

  private fun createFieldList(): List<SearchField> {
    return listOf(
        UpperCaseTextField("accessionNumber", "Accession", ACCESSIONS.NUMBER, nullable = false),
        ActiveField("active", "Active"),
        TextField("bagNumber", "Bag number", BAGS.BAG_NUMBER, SearchTables.Bag),
        TimestampField("checkedInTime", "Checked-In Time", ACCESSIONS.CHECKED_IN_TIME),
        DateField("collectedDate", "Collected on", ACCESSIONS.COLLECTED_DATE),
        TextField("collectionNotes", "Notes (collection)", ACCESSIONS.ENVIRONMENTAL_NOTES),
        IntegerField(
            "cutTestSeedsCompromised",
            "Number of seeds compromised",
            ACCESSIONS.CUT_TEST_SEEDS_COMPROMISED),
        IntegerField("cutTestSeedsEmpty", "Number of seeds empty", ACCESSIONS.CUT_TEST_SEEDS_EMPTY),
        IntegerField(
            "cutTestSeedsFilled", "Number of seeds filled", ACCESSIONS.CUT_TEST_SEEDS_FILLED),
        DateField("dryingEndDate", "Drying end date", ACCESSIONS.DRYING_END_DATE),
        DateField("dryingMoveDate", "Drying move date", ACCESSIONS.DRYING_MOVE_DATE),
        DateField("dryingStartDate", "Drying start date", ACCESSIONS.DRYING_START_DATE),
        EnumField.create("endangered", "Endangered", ACCESSIONS.SPECIES_ENDANGERED_TYPE_ID),
        IntegerField(
            "estimatedSeedsIncoming", "Estimated seeds incoming", ACCESSIONS.EST_SEED_COUNT),
        TextField("family", "Family", FAMILIES.NAME, SearchTables.Family),
        GeolocationField(
            "geolocation",
            "Geolocation",
            GEOLOCATIONS.LATITUDE,
            GEOLOCATIONS.LONGITUDE,
            SearchTables.Geolocation),
        DateField(
            "germinationEndDate",
            "Germination end date",
            GERMINATION_TESTS.END_DATE,
            SearchTables.GerminationTest),
        IntegerField(
            "germinationPercentGerminated",
            "% Viability",
            GERMINATION_TESTS.TOTAL_PERCENT_GERMINATED,
            SearchTables.GerminationTest),
        EnumField.create(
            "germinationSeedType",
            "Seed type",
            GERMINATION_TESTS.SEED_TYPE_ID,
            SearchTables.GerminationTest),
        IntegerField(
            "germinationSeedsGerminated",
            "Number of seeds germinated",
            GERMINATIONS.SEEDS_GERMINATED,
            SearchTables.Germination),
        IntegerField(
            "germinationSeedsSown",
            "Number of seeds sown",
            GERMINATION_TESTS.SEEDS_SOWN,
            SearchTables.GerminationTest),
        DateField(
            "germinationStartDate",
            "Germination start date",
            GERMINATION_TESTS.START_DATE,
            SearchTables.GerminationTest),
        EnumField.create(
            "germinationSubstrate",
            "Germination substrate",
            GERMINATION_TESTS.SUBSTRATE_ID,
            SearchTables.GerminationTest),
        TextField(
            "germinationTestNotes",
            "Notes (germination test)",
            GERMINATION_TESTS.NOTES,
            SearchTables.GerminationTest),
        EnumField.create(
            "germinationTestType",
            "Germination test type",
            GERMINATION_TESTS.TEST_TYPE,
            SearchTables.GerminationTest),
        EnumField.create(
            "germinationTreatment",
            "Germination treatment",
            GERMINATION_TESTS.TREATMENT_ID,
            SearchTables.GerminationTest),
        AccessionIdField("id", "ID"),
        TextField("landowner", "Landowner", ACCESSIONS.COLLECTION_SITE_LANDOWNER),
        DateField(
            "latestGerminationTestDate",
            "Most recent germination test date",
            ACCESSIONS.LATEST_GERMINATION_RECORDING_DATE),
        IntegerField(
            "latestViabilityPercent",
            "Most recent % viability",
            ACCESSIONS.LATEST_VIABILITY_PERCENT),
        DateField("nurseryStartDate", "Nursery start date", ACCESSIONS.NURSERY_START_DATE),
        TextField(
            "primaryCollector",
            "Primary collector",
            COLLECTORS.NAME,
            SearchTables.PrimaryCollector),
        EnumField.create("processingMethod", "Processing method", ACCESSIONS.PROCESSING_METHOD_ID),
        TextField("processingNotes", "Notes (processing)", ACCESSIONS.PROCESSING_NOTES),
        DateField("processingStartDate", "Processing start date", ACCESSIONS.PROCESSING_START_DATE),
        EnumField.create("rare", "Rare", ACCESSIONS.RARE_TYPE_ID),
        DateField("receivedDate", "Received on", ACCESSIONS.RECEIVED_DATE),
        GramsField("remainingGrams", "Remaining (grams)", ACCESSIONS.REMAINING_GRAMS),
        BigDecimalField("remainingQuantity", "Remaining (quantity)", ACCESSIONS.REMAINING_QUANTITY),
        EnumField.create("remainingUnits", "Remaining (units)", ACCESSIONS.REMAINING_UNITS_ID),
        TextField("siteLocation", "Site location", ACCESSIONS.COLLECTION_SITE_NAME),
        EnumField.create("sourcePlantOrigin", "Wild/Outplant", ACCESSIONS.SOURCE_PLANT_ORIGIN_ID),
        TextField("species", "Species", SPECIES.NAME, SearchTables.Species),
        EnumField.create("state", "State", ACCESSIONS.STATE_ID, nullable = false),
        EnumField.create(
            "storageCondition", "Storage condition", ACCESSIONS.TARGET_STORAGE_CONDITION),
        TextField(
            "storageLocation",
            "Storage location",
            STORAGE_LOCATIONS.NAME,
            SearchTables.StorageLocation),
        TextField("storageNotes", "Notes (storage)", ACCESSIONS.STORAGE_NOTES),
        IntegerField("storagePackets", "Number of storage packets", ACCESSIONS.STORAGE_PACKETS),
        DateField("storageStartDate", "Storing start date", ACCESSIONS.STORAGE_START_DATE),
        EnumField.create(
            "targetStorageCondition", "Target %RH", ACCESSIONS.TARGET_STORAGE_CONDITION),
        GramsField("totalGrams", "Total size (grams)", ACCESSIONS.TOTAL_GRAMS),
        BigDecimalField("totalQuantity", "Total size (quantity)", ACCESSIONS.TOTAL_QUANTITY),
        EnumField.create("totalUnits", "Total size (units)", ACCESSIONS.TOTAL_UNITS_ID),
        IntegerField(
            "totalViabilityPercent",
            "Total estimated % viability",
            ACCESSIONS.TOTAL_VIABILITY_PERCENT),
        IntegerField(
            "treesCollectedFrom",
            "Number of trees collected from",
            ACCESSIONS.TREES_COLLECTED_FROM),
        EnumField.create(
            "viabilityTestType",
            "Viability test type (accession)",
            ACCESSION_GERMINATION_TEST_TYPES.GERMINATION_TEST_TYPE_ID,
            SearchTables.AccessionGerminationTestType),
        DateField(
            "withdrawalDate", "Date of withdrawal", WITHDRAWALS.DATE, SearchTables.Withdrawal),
        TextField(
            "withdrawalDestination",
            "Destination",
            WITHDRAWALS.DESTINATION,
            SearchTables.Withdrawal),
        GramsField(
            "withdrawalGrams",
            "Weight of seeds withdrawn (g)",
            WITHDRAWALS.WITHDRAWN_GRAMS,
            SearchTables.Withdrawal),
        TextField(
            "withdrawalNotes", "Notes (withdrawal)", WITHDRAWALS.NOTES, SearchTables.Withdrawal),
        EnumField.create(
            "withdrawalPurpose", "Purpose", WITHDRAWALS.PURPOSE_ID, SearchTables.Withdrawal),
        GramsField(
            "withdrawalRemainingGrams",
            "Weight in grams of seeds remaining (withdrawal)",
            WITHDRAWALS.REMAINING_GRAMS,
            SearchTables.Withdrawal),
        GramsField(
            "withdrawalRemainingQuantity",
            "Weight or count of seeds remaining (withdrawal)",
            WITHDRAWALS.REMAINING_GRAMS,
            SearchTables.Withdrawal),
        EnumField.create(
            "withdrawalRemainingUnits",
            "Units of measurement of quantity remaining (withdrawal)",
            WITHDRAWALS.REMAINING_UNITS_ID,
            SearchTables.Withdrawal),
        BigDecimalField(
            "withdrawalQuantity",
            "Quantity of seeds withdrawn",
            WITHDRAWALS.WITHDRAWN_QUANTITY,
            SearchTables.Withdrawal),
        EnumField.create(
            "withdrawalUnits",
            "Units of measurement of quantity withdrawn",
            WITHDRAWALS.WITHDRAWN_UNITS_ID,
            SearchTables.Withdrawal),
    )
  }

  operator fun get(fieldName: String) = fieldsByName[fieldName]

  /** Base class for fields that map to a single database column. */
  abstract class SingleColumnSearchField<T : Any> : SearchField {
    abstract val databaseField: Field<T?>

    abstract fun getCondition(fieldNode: FieldNode): Condition?

    override val selectFields: List<Field<*>>
      get() = listOf(databaseField)

    override fun getConditions(fieldNode: FieldNode) = listOfNotNull(getCondition(fieldNode))

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

    /**
     * Returns a Condition for a range query on a field with a data type that is compatible with the
     * SQL BETWEEN operator.
     */
    protected fun rangeCondition(values: List<T?>): Condition {
      if (values.size != 2 || values[0] == null && values[1] == null) {
        throw IllegalArgumentException("Range search must have two non-null values")
      }

      return if (values[0] != null && values[1] != null) {
        databaseField.between(values[0], values[1])
      } else if (values[0] == null) {
        databaseField.le(values[1])
      } else {
        databaseField.ge(values[0])
      }
    }
  }

  /** Search field for accession identifiers. */
  class AccessionIdField(override val fieldName: String, override val displayName: String) :
      SingleColumnSearchField<AccessionId>() {
    override val databaseField
      get() = ACCESSIONS.ID
    override val nullable
      get() = false
    override val supportedFilterTypes: Set<SearchFilterType>
      get() = EnumSet.of(SearchFilterType.Exact)
    override val table
      get() = SearchTables.Accession

    override fun getCondition(fieldNode: FieldNode): Condition {
      val allValues = fieldNode.values.filterNotNull().map { id -> AccessionId(id.toLong()) }
      return when (fieldNode.type) {
        SearchFilterType.Exact ->
            if (allValues.isNotEmpty()) databaseField.`in`(allValues) else DSL.falseCondition()
        SearchFilterType.Fuzzy ->
            throw RuntimeException("Fuzzy search not supported for accession IDs")
        SearchFilterType.Range ->
            throw RuntimeException("Range search not supported for accession IDs")
      }
    }
  }

  /**
   * Implements the `active` field. This field doesn't actually exist in the database; it is derived
   * from the `state` field.
   */
  class ActiveField(override val fieldName: String, override val displayName: String) :
      SearchField {
    override val table
      get() = SearchTables.Accession
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

    override val orderByFields: List<Field<*>>
      get() =
          listOf(
              DSL.case_(ACCESSIONS.STATE_ID)
                  .mapValues(AccessionState.values().associateWith { "${it?.toActiveEnum()}" }))

    override fun toString() = fieldName
    override fun hashCode() = fieldName.hashCode()
    override fun equals(other: Any?) = other is ActiveField && other.fieldName == fieldName
  }

  /** Search field for columns with decimal values. */
  class BigDecimalField(
      override val fieldName: String,
      override val displayName: String,
      override val databaseField: TableField<*, BigDecimal?>,
      override val table: SearchTable = SearchTables.Accession
  ) : SingleColumnSearchField<BigDecimal>() {
    override val supportedFilterTypes: Set<SearchFilterType>
      get() = EnumSet.of(SearchFilterType.Exact, SearchFilterType.Range)

    override fun getCondition(fieldNode: FieldNode): Condition {
      val bigDecimalValues = fieldNode.values.map { if (it != null) BigDecimal(it) else null }
      val nonNullValues = bigDecimalValues.filterNotNull()

      return when (fieldNode.type) {
        SearchFilterType.Exact -> {
          DSL.or(
              listOfNotNull(
                  if (nonNullValues.isNotEmpty()) databaseField.`in`(nonNullValues) else null,
                  if (fieldNode.values.any { it == null }) databaseField.isNull else null))
        }
        SearchFilterType.Fuzzy ->
            throw RuntimeException("Fuzzy search not supported for numeric fields")
        SearchFilterType.Range -> rangeCondition(bigDecimalValues)
      }
    }

    override fun computeValue(record: Record) = record[databaseField]?.toPlainString()
  }

  /** Search field for columns that have dates without times or timezones. */
  class DateField(
      override val fieldName: String,
      override val displayName: String,
      override val databaseField: TableField<*, LocalDate?>,
      override val table: SearchTable = SearchTables.Accession,
      override val nullable: Boolean = true
  ) : SearchFields.SingleColumnSearchField<LocalDate>() {
    override val supportedFilterTypes: Set<SearchFilterType>
      get() = EnumSet.of(SearchFilterType.Exact, SearchFilterType.Range)

    override fun getCondition(fieldNode: FieldNode): Condition {
      val dateValues =
          try {
            fieldNode.values.map { if (it != null) LocalDate.parse(it) else null }
          } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("Dates must be in YYYY-MM-DD format")
          }
      val nonNullDates = dateValues.filterNotNull()

      return when (fieldNode.type) {
        SearchFilterType.Exact ->
            DSL.or(
                listOfNotNull(
                    if (nonNullDates.isNotEmpty()) databaseField.`in`(nonNullDates) else null,
                    if (fieldNode.values.any { it == null }) databaseField.isNull else null,
                ))
        SearchFilterType.Fuzzy ->
            throw IllegalArgumentException("Fuzzy search not supported for dates")
        SearchFilterType.Range -> rangeCondition(dateValues)
      }
    }
  }

  /**
   * Search field for columns that refer to reference tables that get compiled to Kotlin enum
   * classes during code generation. Because the contents of these tables are known at compile time,
   * we don't need to join with them and can instead directly include their IDs in our generated
   * SQL.
   */
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

    override fun getCondition(fieldNode: FieldNode): Condition {
      if (fieldNode.type != SearchFilterType.Exact) {
        throw IllegalArgumentException("$fieldName only supports exact searches")
      }

      val enumInstances =
          fieldNode.values.filterNotNull().map {
            byDisplayName[it]
                ?: throw IllegalArgumentException("Value $it not recognized for $fieldName")
          }

      return DSL.or(
          listOfNotNull(
              if (enumInstances.isNotEmpty()) databaseField.`in`(enumInstances) else null,
              if (fieldNode.values.any { it == null }) databaseField.isNull else null))
    }

    override val orderByFields: List<Field<*>>
      get() {
        val displayNames = enumClass.enumConstants!!.associateWith { it.displayName }
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

  /**
   * Search field for geolocation data. Geolocation is represented in search results as a single
   * string value that includes both latitude and longitude. But in the database, those two values
   * are stored as separate columns.
   */
  class GeolocationField(
      override val fieldName: String,
      override val displayName: String,
      private val latitudeField: TableField<*, BigDecimal?>,
      private val longitudeField: TableField<*, BigDecimal?>,
      override val table: SearchTable = SearchTables.Accession,
      override val nullable: Boolean = true
  ) : SearchField {
    override val supportedFilterTypes: Set<SearchFilterType>
      get() = emptySet()

    override val selectFields: List<Field<*>>
      get() = listOf(latitudeField, longitudeField)

    override fun getConditions(fieldNode: FieldNode): List<Condition> {
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

  /**
   * Search field for columns with weights in grams. Supports unit conversions on search criteria.
   */
  class GramsField(
      override val fieldName: String,
      override val displayName: String,
      override val databaseField: TableField<*, BigDecimal?>,
      override val table: SearchTable = SearchTables.Accession
  ) : SingleColumnSearchField<BigDecimal>() {
    override val supportedFilterTypes: Set<SearchFilterType> =
        EnumSet.of(SearchFilterType.Exact, SearchFilterType.Range)

    override fun computeValue(record: Record) = record[databaseField]?.toPlainString()

    override fun getCondition(fieldNode: FieldNode): Condition {
      val bigDecimalValues = fieldNode.values.map { parseGrams(it) }
      val nonNullValues = bigDecimalValues.filterNotNull()

      return when (fieldNode.type) {
        SearchFilterType.Exact -> {
          DSL.or(
              listOfNotNull<@NotNull Condition>(
                  if (nonNullValues.isNotEmpty()) databaseField.`in`(nonNullValues) else null,
                  if (fieldNode.values.any { it == null }) databaseField.isNull else null))
        }
        SearchFilterType.Fuzzy ->
            throw RuntimeException("Fuzzy search not supported for numeric fields")
        SearchFilterType.Range -> rangeCondition(bigDecimalValues)
      }
    }

    private val formatRegex = Regex("([\\d.]+)\\s*(\\D*)")

    private fun parseGrams(value: String?): BigDecimal? {
      if (value == null) {
        return null
      }

      val matches =
          formatRegex.matchEntire(value)
              ?: throw IllegalStateException(
                  "Weight values must be a decimal number optionally followed by a unit name; couldn't interpret $value")

      val number = BigDecimal(matches.groupValues[1])
      val unitsName = matches.groupValues[2].lowercase().replaceFirstChar { it.titlecase() }

      val units =
          if (unitsName.isEmpty()) SeedQuantityUnits.Grams
          else
              SeedQuantityUnits.forDisplayName(unitsName)
                  ?: throw IllegalArgumentException("Unrecognized weight unit in $value")

      return units.toGrams(number)
    }
  }

  /** Search field for numeric columns that don't allow fractional values. */
  class IntegerField(
      override val fieldName: String,
      override val displayName: String,
      override val databaseField: TableField<*, Int?>,
      override val table: SearchTable = SearchTables.Accession,
      override val nullable: Boolean = true
  ) : SingleColumnSearchField<Int>() {
    override val supportedFilterTypes: Set<SearchFilterType>
      get() = EnumSet.of(SearchFilterType.Exact, SearchFilterType.Range)

    override fun getCondition(fieldNode: FieldNode): Condition {
      val intValues = fieldNode.values.map { it?.toInt() }
      val nonNullValues = intValues.filterNotNull()
      return when (fieldNode.type) {
        SearchFilterType.Exact ->
            DSL.or(
                listOfNotNull(
                    if (nonNullValues.isNotEmpty()) databaseField.`in`(nonNullValues) else null,
                    if (fieldNode.values.any { it == null }) databaseField.isNull else null,
                ))
        SearchFilterType.Fuzzy ->
            throw RuntimeException("Fuzzy search not supported for numeric fields")
        SearchFilterType.Range -> rangeCondition(intValues)
      }
    }
  }

  /**
   * Search field for arbitrary text values. This does not differentiate between short values such
   * as a person's name and longer values such as notes.
   */
  inner class TextField(
      override val fieldName: String,
      override val displayName: String,
      override val databaseField: Field<String?>,
      override val table: SearchTable = SearchTables.Accession,
      override val nullable: Boolean = true
  ) : SingleColumnSearchField<String>() {
    override val supportedFilterTypes: Set<SearchFilterType>
      get() = EnumSet.of(SearchFilterType.Exact, SearchFilterType.Fuzzy)

    override fun getCondition(fieldNode: FieldNode): Condition {
      val nonNullValues = fieldNode.values.filterNotNull()
      return when (fieldNode.type) {
        SearchFilterType.Exact ->
            DSL.or(
                listOfNotNull(
                    if (nonNullValues.isNotEmpty()) databaseField.`in`(nonNullValues) else null,
                    if (fieldNode.values.any { it == null }) databaseField.isNull else null))
        SearchFilterType.Fuzzy ->
            DSL.or(
                fieldNode.values.flatMap { value ->
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

  /** Search field for columns that have full timestamps. */
  class TimestampField(
      override val fieldName: String,
      override val displayName: String,
      override val databaseField: TableField<*, Instant?>,
      override val table: SearchTable = SearchTables.Accession,
      override val nullable: Boolean = true
  ) : SearchFields.SingleColumnSearchField<Instant>() {
    override val supportedFilterTypes: Set<SearchFilterType>
      get() = EnumSet.of(SearchFilterType.Exact, SearchFilterType.Range)

    override fun getCondition(fieldNode: FieldNode): Condition {
      val instantValues =
          try {
            fieldNode.values.map { if (it != null) Instant.parse(it) else null }
          } catch (e: DateTimeParseException) {
            throw IllegalArgumentException(
                "Timestamps must be ISO-8601 format with timezone (example: 2021-05-28T18:45:30Z)")
          }
      val nonNullInstants = instantValues.filterNotNull()

      return when (fieldNode.type) {
        SearchFilterType.Exact ->
            DSL.or(
                listOfNotNull(
                    if (nonNullInstants.isNotEmpty()) databaseField.`in`(nonNullInstants) else null,
                    if (fieldNode.values.any { it == null }) databaseField.isNull else null,
                ))
        SearchFilterType.Fuzzy ->
            throw IllegalArgumentException("Fuzzy search not supported for timestamps")
        SearchFilterType.Range -> rangeCondition(instantValues)
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

    override fun getCondition(fieldNode: FieldNode): Condition {
      val values = fieldNode.values.mapNotNull { it?.uppercase() }
      return when (fieldNode.type) {
        SearchFilterType.Exact ->
            DSL.or(
                listOfNotNull(
                    if (values.isNotEmpty()) databaseField.`in`(values) else null,
                    if (fieldNode.values.any { it == null }) databaseField.isNull else null))
        SearchFilterType.Fuzzy ->
            DSL.or(
                fieldNode.values.map { it?.uppercase() }.flatMap { value ->
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
