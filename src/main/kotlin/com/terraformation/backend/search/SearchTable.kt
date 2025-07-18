package com.terraformation.backend.search

import com.terraformation.backend.db.EnumFromReferenceTable
import com.terraformation.backend.db.LocalizableEnum
import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.search.field.AgeField
import com.terraformation.backend.search.field.AliasField
import com.terraformation.backend.search.field.BigDecimalField
import com.terraformation.backend.search.field.BooleanField
import com.terraformation.backend.search.field.CoordinateField
import com.terraformation.backend.search.field.DateField
import com.terraformation.backend.search.field.DoubleField
import com.terraformation.backend.search.field.EnumField
import com.terraformation.backend.search.field.GeometryField
import com.terraformation.backend.search.field.IdWrapperField
import com.terraformation.backend.search.field.IntegerField
import com.terraformation.backend.search.field.LocalizedTextField
import com.terraformation.backend.search.field.LongField
import com.terraformation.backend.search.field.NonLocalizableEnumField
import com.terraformation.backend.search.field.SearchField
import com.terraformation.backend.search.field.StableIdField
import com.terraformation.backend.search.field.TextField
import com.terraformation.backend.search.field.TimestampField
import com.terraformation.backend.search.field.UpperCaseTextField
import com.terraformation.backend.search.field.UriField
import com.terraformation.backend.search.field.WeightField
import com.terraformation.backend.search.field.ZoneIdField
import java.math.BigDecimal
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.jooq.Condition
import org.jooq.Field
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.Table
import org.jooq.TableField
import org.locationtech.jts.geom.Geometry

/**
 * Defines which search fields exist at a particular point in the application's hierarchical data
 * model.
 *
 * The search API allows clients to navigate data in the form of a tree-structured hierarchy of
 * fields that starts at "organizations". For example, accession data is tied to facilities. So when
 * you search for accessions, you are actually asking for a subset of an organization's data. Of
 * that organization's data, you're asking for data associated with a specific project, with one of
 * that's project sites, and one of that site's facilities.
 *
 * We abstract this into a "path" that specifies how to navigate the hierarchy. See
 * [SearchFieldPath] for the implementation details of paths.
 *
 * Given a partial path, the system needs to know what names are valid to add to the path, and
 * whether those names refer to fields with scalar values (numbers, text, etc) or to additional
 * intermediate levels of the hierarchy (e.g., a site or a facility).
 *
 * Each non-leaf node in the hierarchy is associated with a [SearchTable], which is where the search
 * code goes to look up names when it is turning a client-specified field name into a
 * [SearchFieldPath].
 */
abstract class SearchTable {
  /** Scalar fields that are valid in this table. Subclasses must supply this. */
  abstract val fields: List<SearchField>

  /** Sublist fields that are valid in this table. Subclasses must supply this. */
  abstract val sublists: List<SublistField>

  /** The primary key column for the table in question. */
  abstract val primaryKey: TableField<out Record, out Any?>

  /** The jOOQ Table object for the table in question. */
  open val fromTable: Table<out Record>
    get() = primaryKey.table ?: throw IllegalStateException("$primaryKey has no table")

  /**
   * If the user's ability to see a particular row in this table can't be determined directly from
   * the contents of the row itself, the other table that the query needs to left join with in order
   * to check whether the row is visible.
   *
   * Null if the current table has the required information to determine whether the user can see a
   * given row. In that case, [conditionForVisibility] must be non-null.
   */
  open val inheritsVisibilityFrom: SearchTable?
    get() = null

  /**
   * The table's name as it appears in the identifiers of the descriptions of field names in
   * `Messages.properties`. For example, there is a property `search.accessions.active` so the table
   * name would be `accessions`.
   *
   * Default is the class name minus the `Table` suffix and with the first character in lower case.
   */
  open val name: String =
      javaClass.simpleName.substringBeforeLast("Table").replaceFirstChar { it.lowercaseChar() }

  /**
   * Adds a LEFT JOIN clause to a query to connect this table to another table to calculate whether
   * the user is allowed to see a row in this table.
   *
   * This must join to the same table referenced by [inheritsVisibilityFrom].
   *
   * The default no-op implementation will work for any tables that have the required information
   * already, e.g., if a table has a facility ID column, there's no need to join with another table
   * to get a facility ID. The default implementation is only valid if [inheritsVisibilityFrom]
   * returns null.
   */
  open fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    if (inheritsVisibilityFrom == null) {
      return query
    } else {
      throw IllegalStateException(
          "BUG! Must override joinForVisibility if visibility is inherited from another table.")
    }
  }

  /**
   * Returns a condition that restricts this table's values to ones the user has the ability to see.
   * Visibility is usually a question of permissions, but may include other non-permission-related
   * criteria such as an "is deleted" flag.
   *
   * This method can safely assume that [joinForVisibility] was called, so any tables added there
   * are available for use in the condition.
   *
   * If this is null, [inheritsVisibilityFrom] must be non-null.
   */
  open fun conditionForVisibility(): Condition? = null

  /**
   * The default fields to sort on. These are included when doing non-distinct queries; if there are
   * user-supplied sort criteria, these come at the end. This allows us to return stable query
   * results if the user-requested sort fields have duplicate values.
   */
  open val defaultOrderFields: List<OrderField<*>>
    get() =
        fromTable.primaryKey?.fields
            ?: throw IllegalStateException("BUG! No primary key fields found for $fromTable")

  val fieldsWithVariants: List<SearchField> by lazy { fields + fields.mapNotNull { it.raw() } }

  private val fieldsByName: Map<String, SearchField> by lazy {
    fieldsWithVariants.associateBy { it.fieldName }
  }
  private val sublistsByName: Map<String, SublistField> by lazy { sublists.associateBy { it.name } }

  fun getAllFieldNames(prefix: String = ""): Set<String> {
    val myFieldNames = fields.map { prefix + it.fieldName }
    val sublistFieldNames =
        sublistsByName
            .filterValues { it.isTraversedForGetAllFields }
            .flatMap { (name, sublist) -> sublist.searchTable.getAllFieldNames("${prefix}$name.") }

    return (myFieldNames + sublistFieldNames).toSet()
  }

  operator fun get(fieldName: String): SearchField? = fieldsByName[fieldName]

  fun getSublistOrNull(sublistName: String): SublistField? = sublistsByName[sublistName]

  fun aliasField(fieldName: String, targetName: String): AliasField {
    val targetPath = SearchFieldPrefix(this).resolve(targetName)
    return AliasField(fieldName, targetPath)
  }

  /**
   * Returns a [SublistField] pointing to this table for use in cases where there can be multiple
   * values. In other words, returns a [SublistField] that defines a 1:N relationship between
   * another table and this one. For example, `facilities` is a multi-value sublist of `sites`
   * because each site can have multiple facilities.
   */
  fun asMultiValueSublist(
      name: String,
      conditionForMultiset: Condition,
      isTraversedForGetAllFields: Boolean = true,
  ): SublistField {
    return SublistField(
        name = name,
        searchTable = this,
        isMultiValue = true,
        conditionForMultiset = conditionForMultiset,
        isTraversedForGetAllFields = isTraversedForGetAllFields)
  }

  /**
   * Returns a [SublistField] pointing to this table for use in cases where there is only a single
   * value. In other words, returns a [SublistField] that defines a 1:1 or N:1 relationship between
   * another table and this one. For example, `site` is a single-value sublist of `facilities`
   * because each facility is only associated with one site.
   */
  fun asSingleValueSublist(
      name: String,
      conditionForMultiset: Condition,
      isRequired: Boolean = true,
      isTraversedForGetAllFields: Boolean = false,
  ): SublistField {
    return SublistField(
        name = name,
        searchTable = this,
        isMultiValue = false,
        isRequired = isRequired,
        conditionForMultiset = conditionForMultiset,
        isTraversedForGetAllFields = isTraversedForGetAllFields)
  }

  private fun resolveTableOrNull(relativePath: String): SearchTable? {
    val nextAndRest =
        relativePath.split(NESTED_SUBLIST_DELIMITER, FLATTENED_SUBLIST_DELIMITER, limit = 2)
    val nextTable = sublistsByName[nextAndRest[0]]?.searchTable

    return if (nextAndRest.size == 1) {
      nextTable
    } else {
      nextTable?.resolveTableOrNull(nextAndRest[1])
    }
  }

  fun resolveTable(relativePath: String): SearchTable {
    return resolveTableOrNull(relativePath)
        ?: throw IllegalArgumentException("Sublist $relativePath not found")
  }

  fun ageField(
      fieldName: String,
      databaseField: TableField<*, LocalDate?>,
      granularity: AgeField.AgeGranularity,
      clock: Clock,
  ) = AgeField(fieldName, databaseField, this, true, true, granularity, clock)

  fun bigDecimalField(fieldName: String, databaseField: Field<BigDecimal?>) =
      BigDecimalField(fieldName, databaseField, this)

  fun booleanField(fieldName: String, databaseField: Field<Boolean?>) =
      BooleanField(fieldName, databaseField, this)

  fun coordinateField(
      fieldName: String,
      databaseField: Field<Geometry?>,
      vertexIndex: Int,
      axis: CoordinateField.Companion.Axis
  ) = CoordinateField(fieldName, databaseField, vertexIndex, axis, this, true, true)

  fun dateField(fieldName: String, databaseField: Field<LocalDate?>) =
      DateField(fieldName, databaseField, this)

  fun doubleField(fieldName: String, databaseField: Field<Double?>) =
      DoubleField(fieldName, databaseField, this)

  inline fun <E : Enum<E>, reified T : LocalizableEnum<E>> enumField(
      fieldName: String,
      databaseField: Field<T?>,
      localize: Boolean = true,
  ) = EnumField(fieldName, databaseField, this, T::class.java, localize)

  fun geometryField(fieldName: String, databaseField: TableField<*, Geometry?>) =
      GeometryField(fieldName, databaseField, this)

  fun <T : Any> idWrapperField(fieldName: String, databaseField: Field<T?>, fromLong: (Long) -> T) =
      IdWrapperField(fieldName, databaseField, this, fromLong)

  fun stableIdField(
      fieldName: String,
      databaseField: Field<StableId?>,
  ) = StableIdField(fieldName, databaseField, this)

  fun integerField(fieldName: String, databaseField: Field<Int?>, localize: Boolean = true) =
      IntegerField(fieldName, databaseField, this, localize)

  fun localizedTextField(
      fieldName: String,
      databaseField: TableField<*, String?>,
      resourceBundleName: String
  ) = LocalizedTextField(fieldName, databaseField, resourceBundleName, this)

  fun longField(fieldName: String, databaseField: Field<Long?>, nullable: Boolean = true) =
      LongField(fieldName, databaseField, this)

  inline fun <E : Enum<E>, reified T : EnumFromReferenceTable<*, E>> nonLocalizableEnumField(
      fieldName: String,
      databaseField: TableField<*, T?>
  ) = NonLocalizableEnumField(fieldName, databaseField, this, T::class.java)

  fun textField(fieldName: String, databaseField: Field<String?>) =
      TextField(fieldName, databaseField, this)

  fun timestampField(fieldName: String, databaseField: TableField<*, Instant?>) =
      TimestampField(fieldName, databaseField, this)

  fun upperCaseTextField(fieldName: String, databaseField: Field<String?>) =
      UpperCaseTextField(fieldName, databaseField, this)

  fun uriField(fieldName: String, databaseField: Field<URI?>) =
      UriField(fieldName, databaseField, this)

  /**
   * Returns an array of [SearchField]s for a seed quantity: one for each supported weight unit, one
   * for the quantity, and one for the units. This should be expanded into the search table's field
   * list with the `*` operator so we always have a consistent set of fields for weight searches.
   *
   * @param fieldNamePrefix The capitalized units name is appended to this to form the field name.
   *   If this is an empty string, the resulting field name will be the non-capitalized units name.
   *   For example, if this is "foo", the grams field will be "fooGrams", but if this is "", the
   *   grams field will be "grams".
   */
  fun weightFields(
      fieldNamePrefix: String,
      quantityField: TableField<*, BigDecimal?>,
      unitsField: TableField<*, SeedQuantityUnits?>,
      gramsField: Field<BigDecimal?>,
  ): Array<SearchField> {
    fun String.uncapitalize() = replaceFirstChar { it.lowercaseChar() }

    return arrayOf(
        WeightField(
            "${fieldNamePrefix}Grams".uncapitalize(),
            quantityField,
            unitsField,
            gramsField,
            SeedQuantityUnits.Grams,
            this),
        WeightField(
            "${fieldNamePrefix}Kilograms".uncapitalize(),
            quantityField,
            unitsField,
            gramsField,
            SeedQuantityUnits.Kilograms,
            this),
        WeightField(
            "${fieldNamePrefix}Milligrams".uncapitalize(),
            quantityField,
            unitsField,
            gramsField,
            SeedQuantityUnits.Milligrams,
            this),
        WeightField(
            "${fieldNamePrefix}Ounces".uncapitalize(),
            quantityField,
            unitsField,
            gramsField,
            SeedQuantityUnits.Ounces,
            this),
        WeightField(
            "${fieldNamePrefix}Pounds".uncapitalize(),
            quantityField,
            unitsField,
            gramsField,
            SeedQuantityUnits.Pounds,
            this),
        bigDecimalField("${fieldNamePrefix}Quantity".uncapitalize(), quantityField),
        enumField("${fieldNamePrefix}Units".uncapitalize(), unitsField))
  }

  fun zoneIdField(fieldName: String, databaseField: TableField<*, ZoneId?>) =
      ZoneIdField(fieldName, databaseField, this)
}
