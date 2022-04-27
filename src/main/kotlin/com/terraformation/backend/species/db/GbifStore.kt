package com.terraformation.backend.species.db

import com.terraformation.backend.db.tables.pojos.GbifNamesRow
import com.terraformation.backend.db.tables.references.GBIF_NAMES
import com.terraformation.backend.db.tables.references.GBIF_NAME_WORDS
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.jooq.impl.DSL

@ManagedBean
class GbifStore(private val dslContext: DSLContext) {
  /**
   * Returns the species names whose words begin with a list of prefixes.
   *
   * @param [prefixes] Word prefixes to search for. Non-alphabetic characters are ignored, and the
   * search is case-insensitive. The matching words must appear in the same order in the full name
   * as the order of the prefixes; that is, `listOf("a", "b")` will return names where the word that
   * starts with "b" comes after the word that starts with "a" but not the other way around.
   */
  fun findNamesByWordPrefixes(
      prefixes: List<String>,
      scientific: Boolean = true,
      maxResults: Int = 10
  ): List<GbifNamesRow> {
    // Strip non-alphabetic characters and fold to lower case.
    val normalizedPrefixes =
        prefixes
            .map { prefix -> prefix.filter { char -> char.isLetter() } }
            .filter { it.isNotEmpty() }
            .map { it.lowercase() }
    if (normalizedPrefixes.isEmpty()) {
      throw IllegalArgumentException("No letters found in search prefixes")
    }

    // Dynamically construct a query that has a separate join to the gbif_name_words table for each
    // prefix string. For prefixes == listOf("abc", "def") we want to end up with a query like
    //
    // SELECT name
    // FROM gbif_names
    // JOIN gbif_name_words gnw_0 ON gbif_names.id = gnw_0.id
    // JOIN gbif_name_words gnw_1 ON gbif_names.id = gnw_1.id
    // WHERE gnw_0.word LIKE 'abc%'
    // AND gnw_1.word LIKE 'def%'
    // AND gbif_names.is_scientific = TRUE
    // AND LOWER(gbif_names.name) LIKE '%abc%def%'
    // ORDER by gbif_names.name

    val selectFrom = dslContext.selectDistinct(GBIF_NAMES.asterisk()).from(GBIF_NAMES)

    // A separate table alias for each prefix
    val wordsAliases = normalizedPrefixes.indices.map { GBIF_NAME_WORDS.`as`("gnw_$it") }

    // Each alias gets a JOIN clause
    val selectWithJoins =
        wordsAliases.fold(selectFrom) { query, alias ->
          query.join(alias).on(GBIF_NAMES.ID.eq(alias.GBIF_NAME_ID))
        }

    // Each alias gets a LIKE condition to match the corresponding prefix
    val prefixMatchConditions =
        normalizedPrefixes.mapIndexed { index, prefix -> wordsAliases[index].WORD.like("$prefix%") }

    // Make the query sensitive to the order of the search terms
    val orderSensitivePattern = normalizedPrefixes.joinToString("%", "%", "%")

    return selectWithJoins
        .where(prefixMatchConditions)
        .and(GBIF_NAMES.IS_SCIENTIFIC.eq(scientific))
        .and(DSL.lower(GBIF_NAMES.NAME).like(orderSensitivePattern))
        .orderBy(GBIF_NAMES.NAME)
        .limit(maxResults)
        .fetchInto(GbifNamesRow::class.java)
        .filterNotNull()
  }
}
