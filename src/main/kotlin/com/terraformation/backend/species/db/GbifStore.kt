package com.terraformation.backend.species.db

import com.terraformation.backend.db.tables.pojos.GbifNamesRow
import com.terraformation.backend.db.tables.references.GBIF_DISTRIBUTIONS
import com.terraformation.backend.db.tables.references.GBIF_NAMES
import com.terraformation.backend.db.tables.references.GBIF_NAME_WORDS
import com.terraformation.backend.db.tables.references.GBIF_TAXA
import com.terraformation.backend.db.tables.references.GBIF_VERNACULAR_NAMES
import com.terraformation.backend.species.model.GbifTaxonModel
import com.terraformation.backend.species.model.GbifVernacularNameModel
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
    //
    // The LOWER(gbif_names.name) LIKE '%abc%def%' is needed because we want the prefixes to be
    // order-sensitive, but we don't require them to start at the beginning of the name. That is,
    // this search should match species 'Abc def' and 'Xyz abc var. def' but not species 'Def abc'.

    val selectFrom =
        dslContext.selectDistinct(GBIF_NAMES.asterisk()).on(GBIF_NAMES.NAME).from(GBIF_NAMES)

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

  /**
   * Returns GBIF taxon data for the species with a particular scientific name.
   *
   * @param [vernacularNameLanguage] ISO 639-1 two-letter language code. If non-null, filter
   * vernacular names to exclude names in languages other than this. Vernacular names without
   * langauge tags are always included.
   */
  fun fetchOneByScientificName(
      scientificName: String,
      vernacularNameLanguage: String? = null
  ): GbifTaxonModel? {
    val languageCondition =
        if (vernacularNameLanguage != null) {
          GBIF_VERNACULAR_NAMES.LANGUAGE.isNull.or(
              GBIF_VERNACULAR_NAMES.LANGUAGE.eq(vernacularNameLanguage))
        } else {
          null
        }

    val vernacularNamesMultiset =
        DSL.multiset(
                DSL.selectDistinct(
                        GBIF_VERNACULAR_NAMES.VERNACULAR_NAME, GBIF_VERNACULAR_NAMES.LANGUAGE)
                    .from(GBIF_VERNACULAR_NAMES)
                    .where(
                        listOfNotNull(
                            GBIF_VERNACULAR_NAMES.TAXON_ID.eq(GBIF_TAXA.TAXON_ID),
                            languageCondition))
                    .orderBy(GBIF_VERNACULAR_NAMES.VERNACULAR_NAME))
            .convertFrom { result -> result.map { record -> GbifVernacularNameModel(record) } }

    return dslContext
        .select(
            GBIF_TAXA.TAXON_ID,
            GBIF_NAMES.NAME,
            GBIF_TAXA.FAMILY,
            GBIF_DISTRIBUTIONS.THREAT_STATUS,
            vernacularNamesMultiset)
        .from(GBIF_NAMES)
        .join(GBIF_TAXA)
        .on(GBIF_NAMES.TAXON_ID.eq(GBIF_TAXA.TAXON_ID))
        .leftJoin(GBIF_DISTRIBUTIONS)
        .on(GBIF_NAMES.TAXON_ID.eq(GBIF_DISTRIBUTIONS.TAXON_ID))
        .where(GBIF_NAMES.NAME.eq(scientificName))
        .and(GBIF_NAMES.IS_SCIENTIFIC.isTrue)
        .orderBy(GBIF_NAMES.NAME, GBIF_NAMES.TAXON_ID)
        .limit(1)
        .fetchOne { record ->
          GbifTaxonModel(
              taxonId = record[GBIF_TAXA.TAXON_ID]
                      ?: throw IllegalArgumentException("Taxon ID must be non-null"),
              scientificName = record[GBIF_NAMES.NAME]
                      ?: throw IllegalArgumentException("Scientific name must be non-null"),
              familyName = record[GBIF_TAXA.FAMILY]
                      ?: throw IllegalArgumentException("Family name must be non-null"),
              vernacularNames = record[vernacularNamesMultiset] ?: emptyList(),
              threatStatus = record[GBIF_DISTRIBUTIONS.THREAT_STATUS],
          )
        }
  }
}
