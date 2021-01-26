package com.terraformation.seedbank.search

import com.terraformation.seedbank.api.seedbank.SearchRequestPayload
import com.terraformation.seedbank.api.seedbank.SearchResponsePayload
import com.terraformation.seedbank.db.tables.references.ACCESSION
import com.terraformation.seedbank.services.perClassLogger
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.conf.ParamType
import org.jooq.impl.DSL

@ManagedBean
class SearchService(private val dslContext: DSLContext, private val searchFields: SearchFields) {
  private val log = perClassLogger()

  fun search(criteria: SearchRequestPayload): SearchResponsePayload {
    val directlyReferencedTables =
        (criteria.fields.map { it.table }.toSet() +
            (criteria.filters?.map { it.field.table }?.toSet() ?: emptySet()) +
            (criteria.sortFields?.map { it.table }?.toSet() ?: emptySet()))
    val dependencyTables =
        directlyReferencedTables.flatMap { it.dependsOn() }.toSet() - directlyReferencedTables

    val fieldNames = setOf("accessionNumber") + criteria.fields.map { it.fieldName }.toSet()
    val fields =
        fieldNames.map {
          searchFields[it] ?: throw IllegalArgumentException("Unknown field name $it")
        }
    val databaseFields = fields.flatMap { it.selectFields }.toSet()

    val conditions = criteria.filters?.flatMap { it.toFieldConditions() } ?: emptyList()
    val orderBy =
        (criteria.sortFields?.flatMap { it.orderByFields }
            ?: emptyList()) + listOf(ACCESSION.NUMBER)

    var query: SelectJoinStep<out Record> = dslContext.select(databaseFields).from(ACCESSION)

    dependencyTables.forEach { table -> query = table.addJoin(query) }
    directlyReferencedTables.forEach { table -> query = table.addJoin(query) }

    // TODO: Better cursor support. Should remember the most recent values of the sort fields
    //       and pass them to skip(). For now, just treat the cursor as an offset.
    val offset = criteria.cursor?.toIntOrNull() ?: 0

    // Query one more row than the limit so we can tell the client whether or not there are
    // additional pages of results.
    val fullQuery =
        query.where(conditions).orderBy(orderBy).limit(criteria.count + 1).offset(offset)

    log.info("SQL query: ${fullQuery.getSQL(ParamType.INLINED)}")

    val records = fullQuery.fetch()
    val results =
        records.map { record ->
          fields
              .mapNotNull { field ->
                val value = field.computeValue(record)
                if (value != null) {
                  field.fieldName to value.toString()
                } else {
                  null
                }
              }
              .toMap()
        }

    val cursor =
        if (results.size > criteria.count) {
          "${offset + criteria.count}"
        } else {
          null
        }
    return SearchResponsePayload(cursor = cursor, results = results.take(criteria.count))
  }

  fun <T> fetchFieldValues(
      field: SearchField<T>,
      filters: List<SearchFilter>,
      limit: Int = 50
  ): List<T> {
    val dependencyTables = field.table.dependsOn()
    val selectFields =
        field.selectFields +
            field.orderByFields.mapIndexed { index, orderByField ->
              orderByField.`as`(DSL.field("field$index"))
            }

    var query: SelectJoinStep<out Record> = dslContext.selectDistinct(selectFields).from(ACCESSION)

    dependencyTables.forEach { table -> query = table.addJoin(query) }
    if (field.table != SearchTables.Accession) {
      query = field.table.addJoin(query)
    }

    val fullQuery =
        query
            .where(filters.flatMap { it.toFieldConditions() })
            .orderBy(field.orderByFields.mapIndexed { index, _ -> DSL.field("field$index") })
            .limit(limit + 1)

    log.info("SQL query: ${fullQuery.getSQL(ParamType.INLINED)}")

    return fullQuery.fetch { field.computeValue(it) }.distinct()
  }
}
