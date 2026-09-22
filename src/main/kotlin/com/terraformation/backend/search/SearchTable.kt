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
import com.terraformation.backend.search.field.DatabaseFieldSupplier
import com.terraformation.backend.search.field.DateField
import com.terraformation.backend.search.field.DoubleField
import com.terraformation.backend.search.field.EnumField
import com.terraformation.backend.search.field.GeometryField
import com.terraformation.backend.search.field.IdWrapperField
import com.terraformation.backend.search.field.IntegerField
import com.terraformation.backend.search.field.LocalDateTimeField
import com.terraformation.backend.search.field.LocalizedTextField
import com.terraformation.backend.search.field.LongField
import com.terraformation.backend.search.field.NonLocalizableEnumField
import com.terraformation.backend.search.field.NullMessageField
import com.terraformation.backend.search.field.SearchField
import com.terraformation.backend.search.field.StableIdField
import com.terraformation.backend.search.field.TextField
import com.terraformation.backend.search.field.TimestampField
import com.terraformation.backend.search.field.UpperCaseTextField
import com.terraformation.backend.search.field.UriField
import com.terraformation.backend.search.field.WeightField
import com.terraformation.backend.search.field.ZoneIdField
import com.terraformation.backend.search.field.column
import com.terraformation.backend.search.field.columnSupplier
import com.terraformation.backend.search.field.columnsEqual
import java.math.BigDecimal
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
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
  abstract val primaryKey: Field<out Any?>

  /**
   * The individual columns that make up [primaryKey]. jOOQ represents a composite primary key as a
   * single embeddable column, but it doesn't expose embeddable columns on aliased tables at all, so
   * queries have to refer to the component columns instead.
   */
  open val primaryKeyFields: List<Field<*>>
    get() =
        if (primaryKey.dataType.isEmbeddable) {
          primaryKey.dataType.row!!.fields().toList()
        } else {
          listOf(primaryKey)
        }

  /** The jOOQ Table object for the table in question. */
  open val fromTable: Table<out Record>
    get() =
        (primaryKey as? TableField<out Record, *>)?.table
            ?: throw IllegalStateException("$primaryKey has no table")

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
   * Adds a JOIN clause to a query to connect this table to another table to calculate whether the
   * user is allowed to see a row in this table.
   *
   * This must join to the same table referenced by [inheritsVisibilityFrom].
   *
   * The default no-op implementation will work for any tables that have the required information
   * already, e.g., if a table has a facility ID column, there's no need to join with another table
   * to get a facility ID. The default implementation is only valid if [inheritsVisibilityFrom]
   * returns null.
   *
   * @param table The instance of this table that the query is reading from. The table may be an
   *   alias; implementations should look columns up using [column] rather than referring to the
   *   jOOQ columns directly.
   */
  open fun <T : Record> joinForVisibility(
      query: SelectJoinStep<T>,
      table: Table<*>,
  ): SelectJoinStep<T> {
    if (inheritsVisibilityFrom == null) {
      return query
    } else {
      throw IllegalStateException(
          "BUG! Must override joinForVisibility if visibility is inherited from another table."
      )
    }
  }

  /** Adds visibility JOIN clauses for the instance of this table that the query is reading from. */
  fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> =
      joinForVisibility(query, fromTable)

  /**
   * Returns a condition that restricts this table's values to ones the user has the ability to see.
   * Visibility is usually a question of permissions, but may include other non-permission-related
   * criteria such as an "is deleted" flag.
   *
   * This method can safely assume that [joinForVisibility] was called, so any tables added there
   * are available for use in the condition.
   *
   * If this is null, [inheritsVisibilityFrom] must be non-null.
   *
   * @param table The instance of this table that the query is reading from. The table may be an
   *   alias; implementations should look columns up using [column] rather than referring to the
   *   jOOQ columns directly.
   */
  open fun conditionForVisibility(table: Table<*>): Condition? = null

  /** Returns the visibility condition for the instance of this table the query is reading from. */
  fun conditionForVisibility(): Condition? = conditionForVisibility(fromTable)

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
   * Returns a [SublistField] pointing to the next table in a sublist path, in cases where there can
   * be multiple values in that table. In other words, returns a [SublistField] that defines a 1:N
   * relationship between this table and another one.
   */
  fun <T> asMultiValueSublist(
      name: String,
      thisTableField: Field<T>,
      otherTableField: Field<T>,
      isTraversedForGetAllFields: Boolean = true,
  ): SublistField =
      asMultiValueSublist(name, isTraversedForGetAllFields) { thisTable, otherTable ->
        columnsEqual(thisTable, thisTableField, otherTable, otherTableField)
      }

  /**
   * Returns a [SublistField] for a 1:N relationship using a dynamically rendered join condition.
   * See [SublistField.getConditionForMultiset].
   */
  fun asMultiValueSublist(
      name: String,
      isTraversedForGetAllFields: Boolean = true,
      getConditionForMultiset: SublistField.GetConditionForMultiset,
  ): SublistField {
    return SublistField(
        name = name,
        searchTable = this,
        isMultiValue = true,
        getConditionForMultiset = getConditionForMultiset,
        isTraversedForGetAllFields = isTraversedForGetAllFields,
    )
  }

  /**
   * Returns a [SublistField] pointing to the next table in a sublist path, in cases where there can
   * only be a single value in that table. In other words, returns a [SublistField] that defines a
   * 1:1 or N:1 relationship between this table and another one.
   */
  fun <T> asSingleValueSublist(
      name: String,
      thisTableField: Field<T>,
      otherTableField: Field<T>,
      isTraversedForGetAllFields: Boolean = false,
  ): SublistField =
      asSingleValueSublist(name, isTraversedForGetAllFields) { thisTable, otherTable ->
        columnsEqual(thisTable, thisTableField, otherTable, otherTableField)
      }

  /**
   * Returns a [SublistField] for an N:1 or 1:1 relationship using a dynamically rendered join
   * condition. See [SublistField.getConditionForMultiset].
   */
  fun asSingleValueSublist(
      name: String,
      isTraversedForGetAllFields: Boolean = false,
      getConditionForMultiset: SublistField.GetConditionForMultiset,
  ): SublistField {
    return SublistField(
        name = name,
        searchTable = this,
        isMultiValue = false,
        getConditionForMultiset = getConditionForMultiset,
        isTraversedForGetAllFields = isTraversedForGetAllFields,
    )
  }

  open fun withAlias(alias: String): AliasedSearchTable {
    return AliasedSearchTable(this, alias)
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

  // Each of the field-definition helpers below comes in two flavors: one that takes a jOOQ column
  // and one that takes a function to derive a jOOQ field from an instance of this table. Use the
  // column flavor for fields that map directly to columns of this table, and the function flavor
  // for fields whose values are computed by SQL expressions. See [DatabaseFieldSelector]. In the
  // function flavors, the function is the last parameter so it can be passed as a trailing lambda.

  fun ageField(
      fieldName: String,
      databaseField: Field<LocalDate?>,
      granularity: AgeField.AgeGranularity,
      clock: Clock,
  ) = ageField(fieldName, granularity, clock, columnSupplier(databaseField))

  fun ageField(
      fieldName: String,
      granularity: AgeField.AgeGranularity,
      clock: Clock,
      getDatabaseField: DatabaseFieldSupplier<LocalDate>,
  ) = AgeField(fieldName, getDatabaseField, this, true, true, granularity, clock)

  fun bigDecimalField(fieldName: String, databaseField: Field<BigDecimal?>) =
      bigDecimalField(fieldName, columnSupplier(databaseField))

  fun bigDecimalField(fieldName: String, getDatabaseField: DatabaseFieldSupplier<BigDecimal>) =
      BigDecimalField(fieldName, getDatabaseField, this)

  fun booleanField(fieldName: String, databaseField: Field<Boolean?>) =
      booleanField(fieldName, columnSupplier(databaseField))

  fun booleanField(fieldName: String, getDatabaseField: DatabaseFieldSupplier<Boolean>) =
      BooleanField(fieldName, getDatabaseField, this)

  fun coordinateField(
      fieldName: String,
      databaseField: Field<Geometry?>,
      vertexIndex: Int,
      axis: CoordinateField.Companion.Axis,
  ) = coordinateField(fieldName, vertexIndex, axis, columnSupplier(databaseField))

  fun coordinateField(
      fieldName: String,
      vertexIndex: Int,
      axis: CoordinateField.Companion.Axis,
      getDatabaseField: DatabaseFieldSupplier<Geometry>,
  ) = CoordinateField(fieldName, getDatabaseField, vertexIndex, axis, this, true, true)

  fun dateField(fieldName: String, databaseField: Field<LocalDate?>) =
      dateField(fieldName, columnSupplier(databaseField))

  fun dateField(fieldName: String, getDatabaseField: DatabaseFieldSupplier<LocalDate>) =
      DateField(fieldName, getDatabaseField, this)

  fun doubleField(fieldName: String, databaseField: Field<Double?>) =
      doubleField(fieldName, columnSupplier(databaseField))

  fun doubleField(fieldName: String, getDatabaseField: DatabaseFieldSupplier<Double>) =
      DoubleField(fieldName, getDatabaseField, this)

  inline fun <E : Enum<E>, reified T : LocalizableEnum<E>> enumField(
      fieldName: String,
      databaseField: Field<T?>,
      localize: Boolean = true,
  ) = enumField<E, T>(fieldName, localize, columnSupplier(databaseField))

  inline fun <E : Enum<E>, reified T : LocalizableEnum<E>> enumField(
      fieldName: String,
      localize: Boolean = true,
      noinline getDatabaseField: DatabaseFieldSupplier<T>,
  ) = EnumField(fieldName, getDatabaseField, this, T::class.java, localize)

  fun geometryField(fieldName: String, databaseField: Field<Geometry?>) =
      geometryField(fieldName, columnSupplier(databaseField))

  fun geometryField(fieldName: String, getDatabaseField: DatabaseFieldSupplier<Geometry>) =
      GeometryField(fieldName, getDatabaseField, this)

  fun <T : Any> idWrapperField(fieldName: String, databaseField: Field<T?>, fromLong: (Long) -> T) =
      idWrapperField(fieldName, fromLong, columnSupplier(databaseField))

  fun <T : Any> idWrapperField(
      fieldName: String,
      fromLong: (Long) -> T,
      getDatabaseField: DatabaseFieldSupplier<T>,
  ) = IdWrapperField(fieldName, getDatabaseField, this, fromLong)

  fun stableIdField(fieldName: String, databaseField: Field<StableId?>) =
      stableIdField(fieldName, columnSupplier(databaseField))

  fun stableIdField(fieldName: String, getDatabaseField: DatabaseFieldSupplier<StableId>) =
      StableIdField(fieldName, getDatabaseField, this)

  fun integerField(fieldName: String, databaseField: Field<Int?>, localize: Boolean = true) =
      integerField(fieldName, localize, columnSupplier(databaseField))

  fun integerField(
      fieldName: String,
      localize: Boolean = true,
      getDatabaseField: DatabaseFieldSupplier<Int>,
  ) = IntegerField(fieldName, getDatabaseField, this, localize)

  fun localDateTimeField(fieldName: String, databaseField: Field<LocalDateTime?>) =
      localDateTimeField(fieldName, columnSupplier(databaseField))

  fun localDateTimeField(
      fieldName: String,
      getDatabaseField: DatabaseFieldSupplier<LocalDateTime>,
  ) = LocalDateTimeField(fieldName, getDatabaseField, this)

  fun <T : Any> localizedTextField(
      fieldName: String,
      databaseField: Field<T?>,
      resourceBundleName: String,
      prefix: String? = null,
  ) = localizedTextField(fieldName, resourceBundleName, prefix, columnSupplier(databaseField))

  fun <T : Any> localizedTextField(
      fieldName: String,
      resourceBundleName: String,
      prefix: String? = null,
      getDatabaseField: DatabaseFieldSupplier<T>,
  ) = LocalizedTextField(fieldName, getDatabaseField, resourceBundleName, prefix, this)

  fun longField(fieldName: String, databaseField: Field<Long?>, nullable: Boolean = true) =
      longField(fieldName, columnSupplier(databaseField))

  fun longField(fieldName: String, getDatabaseField: DatabaseFieldSupplier<Long>) =
      LongField(fieldName, getDatabaseField, this)

  inline fun <reified T : EnumFromReferenceTable<*, T>> nonLocalizableEnumField(
      fieldName: String,
      databaseField: Field<T?>,
  ) = nonLocalizableEnumField<T>(fieldName, columnSupplier(databaseField))

  inline fun <reified T : EnumFromReferenceTable<*, T>> nonLocalizableEnumField(
      fieldName: String,
      noinline getDatabaseField: DatabaseFieldSupplier<T>,
  ) = NonLocalizableEnumField(fieldName, getDatabaseField, this, T::class.java)

  fun nullMessageField(
      original: SearchField,
      nullKey: String,
      resourceBundleName: String = "i18n/Messages",
  ) = NullMessageField(original, nullKey, resourceBundleName)

  fun textField(
      fieldName: String,
      databaseField: Field<String?>,
      collation: String? = null,
  ) = textField(fieldName, collation, columnSupplier(databaseField))

  fun textField(
      fieldName: String,
      collation: String? = null,
      getDatabaseField: DatabaseFieldSupplier<String>,
  ) = TextField(fieldName, getDatabaseField, this, collation)

  fun timestampField(fieldName: String, databaseField: Field<Instant?>) =
      timestampField(fieldName, columnSupplier(databaseField))

  fun timestampField(fieldName: String, getDatabaseField: DatabaseFieldSupplier<Instant>) =
      TimestampField(fieldName, getDatabaseField, this)

  fun upperCaseTextField(fieldName: String, databaseField: Field<String?>) =
      upperCaseTextField(fieldName, columnSupplier(databaseField))

  fun upperCaseTextField(fieldName: String, getDatabaseField: DatabaseFieldSupplier<String>) =
      UpperCaseTextField(fieldName, getDatabaseField, this)

  fun uriField(fieldName: String, databaseField: Field<URI?>) =
      uriField(fieldName, columnSupplier(databaseField))

  fun uriField(fieldName: String, getDatabaseField: DatabaseFieldSupplier<URI>) =
      UriField(fieldName, getDatabaseField, this)

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
      quantityField: Field<BigDecimal?>,
      unitsField: Field<SeedQuantityUnits?>,
      gramsField: Field<BigDecimal?>,
  ): Array<SearchField> {
    fun String.uncapitalize() = replaceFirstChar { it.lowercaseChar() }

    val getQuantityField = columnSupplier(quantityField)
    val getUnitsField = columnSupplier(unitsField)
    val getGramsField = columnSupplier(gramsField)

    fun weightField(unitsName: String, units: SeedQuantityUnits) =
        WeightField(
            "$fieldNamePrefix$unitsName".uncapitalize(),
            getQuantityField,
            getUnitsField,
            getGramsField,
            units,
            this,
        )

    return arrayOf(
        weightField("Grams", SeedQuantityUnits.Grams),
        weightField("Kilograms", SeedQuantityUnits.Kilograms),
        weightField("Milligrams", SeedQuantityUnits.Milligrams),
        weightField("Ounces", SeedQuantityUnits.Ounces),
        weightField("Pounds", SeedQuantityUnits.Pounds),
        bigDecimalField("${fieldNamePrefix}Quantity".uncapitalize(), getQuantityField),
        enumField("${fieldNamePrefix}Units".uncapitalize(), getDatabaseField = getUnitsField),
    )
  }

  fun zoneIdField(fieldName: String, databaseField: Field<ZoneId?>) =
      zoneIdField(fieldName, columnSupplier(databaseField))

  fun zoneIdField(fieldName: String, getDatabaseField: DatabaseFieldSupplier<ZoneId>) =
      ZoneIdField(fieldName, getDatabaseField, this)
}
