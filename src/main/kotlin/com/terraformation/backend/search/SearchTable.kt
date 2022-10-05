package com.terraformation.backend.search

import com.terraformation.backend.db.EnumFromReferenceTable
import com.terraformation.backend.search.field.AgeField
import com.terraformation.backend.search.field.AliasField
import com.terraformation.backend.search.field.BigDecimalField
import com.terraformation.backend.search.field.BooleanField
import com.terraformation.backend.search.field.DateField
import com.terraformation.backend.search.field.DoubleField
import com.terraformation.backend.search.field.EnumField
import com.terraformation.backend.search.field.GeometryField
import com.terraformation.backend.search.field.GramsField
import com.terraformation.backend.search.field.IdWrapperField
import com.terraformation.backend.search.field.IntegerField
import com.terraformation.backend.search.field.LongField
import com.terraformation.backend.search.field.MappedField
import com.terraformation.backend.search.field.SearchField
import com.terraformation.backend.search.field.TextField
import com.terraformation.backend.search.field.TimestampField
import com.terraformation.backend.search.field.UpperCaseTextField
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import net.postgis.jdbc.geometry.Geometry
import org.jooq.Condition
import org.jooq.Field
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.Table
import org.jooq.TableField

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

  /** Returns a condition for scoping the table's search results where relevant. */
  abstract fun conditionForScope(scope: SearchScope): Condition?

  /**
   * The default fields to sort on. These are included when doing non-distinct queries; if there are
   * user-supplied sort criteria, these come at the end. This allows us to return stable query
   * results if the user-requested sort fields have duplicate values.
   */
  open val defaultOrderFields: List<OrderField<*>>
    get() =
        fromTable.primaryKey?.fields
            ?: throw IllegalStateException("BUG! No primary key fields found for $fromTable")

  private val fieldsByName: Map<String, SearchField> by lazy { fields.associateBy { it.fieldName } }
  private val sublistsByName: Map<String, SublistField> by lazy { sublists.associateBy { it.name } }

  fun getAllFieldNames(prefix: String = ""): Set<String> {
    val myFieldNames = fields.map { prefix + it.fieldName }
    val sublistFieldNames =
        sublistsByName
            .filterValues { it.isMultiValue }
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
  fun asMultiValueSublist(name: String, conditionForMultiset: Condition): SublistField {
    return SublistField(
        name = name,
        searchTable = this,
        isMultiValue = true,
        conditionForMultiset = conditionForMultiset)
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
      isRequired: Boolean = true
  ): SublistField {
    return SublistField(
        name = name,
        searchTable = this,
        isMultiValue = false,
        isRequired = isRequired,
        conditionForMultiset = conditionForMultiset)
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
      displayName: String,
      databaseField: TableField<*, LocalDate?>,
      granularity: AgeField.AgeGranularity,
      clock: Clock,
      nullable: Boolean = true,
  ) = AgeField(fieldName, displayName, databaseField, this, nullable, granularity, clock)

  fun bigDecimalField(
      fieldName: String,
      displayName: String,
      databaseField: TableField<*, BigDecimal?>,
  ) = BigDecimalField(fieldName, displayName, databaseField, this)

  fun booleanField(
      fieldName: String,
      displayName: String,
      databaseField: TableField<*, Boolean?>,
      nullable: Boolean = true
  ) = BooleanField(fieldName, displayName, databaseField, this, nullable)

  fun dateField(
      fieldName: String,
      displayName: String,
      databaseField: TableField<*, LocalDate?>,
      nullable: Boolean = false
  ) = DateField(fieldName, displayName, databaseField, this, nullable)

  fun doubleField(
      fieldName: String,
      displayName: String,
      databaseField: TableField<*, Double?>,
      nullable: Boolean = false
  ) = DoubleField(fieldName, displayName, databaseField, this, nullable)

  inline fun <E : Enum<E>, reified T : EnumFromReferenceTable<E>> enumField(
      fieldName: String,
      displayName: String,
      databaseField: TableField<*, T?>,
      nullable: Boolean = true
  ) = EnumField(fieldName, displayName, databaseField, this, T::class.java, nullable)

  fun geometryField(
      fieldName: String,
      displayName: String,
      databaseField: TableField<*, Geometry?>,
      nullable: Boolean = true
  ) = GeometryField(fieldName, displayName, databaseField, this, nullable)

  fun gramsField(
      fieldName: String,
      displayName: String,
      databaseField: TableField<*, BigDecimal?>
  ) = GramsField(fieldName, displayName, databaseField, this)

  fun <T : Any> idWrapperField(
      fieldName: String,
      displayName: String,
      databaseField: TableField<*, T?>,
      fromLong: (Long) -> T
  ) = IdWrapperField(fieldName, displayName, databaseField, this, fromLong)

  fun integerField(
      fieldName: String,
      displayName: String,
      databaseField: TableField<*, Int?>,
      nullable: Boolean = true
  ) = IntegerField(fieldName, displayName, databaseField, this, nullable)

  fun longField(
      fieldName: String,
      displayName: String,
      databaseField: TableField<*, Long?>,
      nullable: Boolean = true
  ) = LongField(fieldName, displayName, databaseField, this, nullable)

  fun <T : Any> mappedField(
      fieldName: String,
      displayName: String,
      databaseField: TableField<*, T?>,
      nullable: Boolean = true,
      convertSearchFilter: (String) -> T?,
      convertDatabaseValue: (T) -> String?
  ) =
      MappedField(
          fieldName,
          displayName,
          databaseField,
          this,
          nullable,
          convertSearchFilter,
          convertDatabaseValue)

  fun textField(
      fieldName: String,
      displayName: String,
      databaseField: Field<String?>,
      nullable: Boolean = true
  ) = TextField(fieldName, displayName, databaseField, this, nullable)

  fun timestampField(
      fieldName: String,
      displayName: String,
      databaseField: TableField<*, Instant?>,
      nullable: Boolean = true
  ) = TimestampField(fieldName, displayName, databaseField, this, nullable)

  fun upperCaseTextField(
      fieldName: String,
      displayName: String,
      databaseField: Field<String?>,
      nullable: Boolean = true
  ) = UpperCaseTextField(fieldName, displayName, databaseField, this, nullable)
}
