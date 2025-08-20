package com.terraformation.backend.species.db

import com.terraformation.backend.db.default_schema.SpeciesProblemField
import com.terraformation.backend.db.default_schema.SpeciesProblemType
import com.terraformation.backend.db.default_schema.tables.pojos.GbifNamesRow
import com.terraformation.backend.db.default_schema.tables.pojos.SpeciesProblemsRow
import com.terraformation.backend.db.default_schema.tables.references.GBIF_DISTRIBUTIONS
import com.terraformation.backend.db.default_schema.tables.references.GBIF_NAMES
import com.terraformation.backend.db.default_schema.tables.references.GBIF_NAME_WORDS
import com.terraformation.backend.db.default_schema.tables.references.GBIF_TAXA
import com.terraformation.backend.db.default_schema.tables.references.GBIF_VERNACULAR_NAMES
import com.terraformation.backend.db.likeFuzzy
import com.terraformation.backend.db.similarity
import com.terraformation.backend.db.unaccent
import com.terraformation.backend.species.model.GbifTaxonModel
import com.terraformation.backend.species.model.GbifVernacularNameModel
import com.terraformation.backend.util.removeDiacritics
import jakarta.inject.Named
import org.jooq.DSLContext
import org.jooq.impl.DSL

@Named
class GbifStore(private val dslContext: DSLContext) {
  /**
   * Returns the species names whose words begin with a list of prefixes.
   *
   * @param [prefixes] Word prefixes to search for. Non-alphabetic characters are ignored, and the
   *   search is case-insensitive. The matching words must appear in the same order in the full name
   *   as the order of the prefixes; that is, `listOf("a", "b")` will return names where the word
   *   that starts with "b" comes after the word that starts with "a" but not the other way around.
   */
  fun findNamesByWordPrefixes(
      prefixes: List<String>,
      scientific: Boolean = true,
      maxResults: Int = 10,
  ): List<GbifNamesRow> {
    // Strip non-alphabetic characters and diacritics, and fold to lower case.
    val normalizedPrefixes =
        prefixes
            .map { prefix -> prefix.removeDiacritics().filter { char -> char.isLetter() } }
            .filter { it.isNotEmpty() }
            .map { it.lowercase() }
    if (normalizedPrefixes.isEmpty()) {
      throw IllegalArgumentException("No letters found in search prefixes")
    }

    // Dynamically construct a query that has a separate join to the gbif_name_words table for each
    // prefix string. For prefixes == listOf("abc", "def") we want to end up with a query like
    //
    // SELECT *
    // FROM gbif_names
    // JOIN gbif_name_words gnw_0 ON gbif_names.id = gnw_0.id
    // JOIN gbif_name_words gnw_1 ON gbif_names.id = gnw_1.id
    // WHERE gnw_0.word LIKE 'abc%'
    // AND gnw_1.word LIKE 'def%'
    // AND gbif_names.is_scientific = TRUE
    // AND LOWER(gbif_names.name) LIKE '%abc%def%'
    // ORDER BY CASE WHEN LOWER(gbif_names.name) LIKE 'abc%' THEN 1 ELSE 2 END,
    //     LOWER(gbif_names.name)
    //
    // The LOWER(gbif_names.name) LIKE '%abc%def%' is needed because we want the prefixes to be
    // order-sensitive, but we don't require them to start at the beginning of the name. That is,
    // this search should match species 'Abc def' and 'Xyz abc var. def' but not species 'Def abc'.
    //
    // The ORDER BY CASE causes results where the first search prefix matches the first word of
    // the species name to be sorted above matches on other words in the species name.

    val firstWordPattern = normalizedPrefixes.first() + "%"
    val firstWordSortPosition =
        DSL.case_()
            .`when`(DSL.lower(GBIF_NAMES.NAME.unaccent()).like(firstWordPattern), 1)
            .else_(2)
            .`as`("first_word_sort_position")

    val selectFrom =
        dslContext
            .selectDistinct(GBIF_NAMES.asterisk(), firstWordSortPosition)
            .on(firstWordSortPosition, GBIF_NAMES.NAME)
            .from(GBIF_NAMES)

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
        .and(DSL.lower(GBIF_NAMES.NAME.unaccent()).like(orderSensitivePattern))
        .orderBy(firstWordSortPosition, GBIF_NAMES.NAME)
        .limit(maxResults)
        .fetchInto(GbifNamesRow::class.java)
  }

  /**
   * Returns GBIF taxon data for the species with a particular scientific name.
   *
   * @param [vernacularNameLanguage] ISO 639-1 two-letter language code. If non-null, filter
   *   vernacular names to exclude names in languages other than this. Vernacular names without
   *   langauge tags are always included.
   */
  fun fetchOneByScientificName(
      scientificName: String,
      vernacularNameLanguage: String? = null,
  ): GbifTaxonModel? {
    val languageCondition =
        if (vernacularNameLanguage != null) {
          GBIF_VERNACULAR_NAMES.LANGUAGE.isNull.or(
              GBIF_VERNACULAR_NAMES.LANGUAGE.eq(vernacularNameLanguage)
          )
        } else {
          null
        }

    val vernacularNamesMultiset =
        DSL.multiset(
                DSL.selectDistinct(
                        GBIF_VERNACULAR_NAMES.VERNACULAR_NAME,
                        GBIF_VERNACULAR_NAMES.LANGUAGE,
                    )
                    .from(GBIF_VERNACULAR_NAMES)
                    .where(
                        listOfNotNull(
                            GBIF_VERNACULAR_NAMES.TAXON_ID.eq(GBIF_TAXA.TAXON_ID),
                            languageCondition,
                        )
                    )
                    .orderBy(GBIF_VERNACULAR_NAMES.VERNACULAR_NAME)
            )
            .convertFrom { result -> result.map { record -> GbifVernacularNameModel(record) } }

    return dslContext
        .select(
            GBIF_TAXA.TAXON_ID,
            GBIF_NAMES.NAME,
            GBIF_TAXA.FAMILY,
            GBIF_DISTRIBUTIONS.THREAT_STATUS,
            vernacularNamesMultiset,
        )
        .from(GBIF_NAMES)
        .join(GBIF_TAXA)
        .on(GBIF_NAMES.TAXON_ID.eq(GBIF_TAXA.TAXON_ID))
        .leftJoin(GBIF_DISTRIBUTIONS)
        .on(GBIF_NAMES.TAXON_ID.eq(GBIF_DISTRIBUTIONS.TAXON_ID))
        .where(GBIF_NAMES.NAME.eq(scientificName))
        .and(GBIF_NAMES.IS_SCIENTIFIC.isTrue)
        .orderBy(
            DSL.case_()
                .`when`(GBIF_TAXA.TAXONOMIC_STATUS.eq(TAXONOMIC_STATUS_ACCEPTED), 1)
                .else_(2),
            GBIF_NAMES.TAXON_ID,
        )
        .limit(1)
        .fetchOne { record ->
          GbifTaxonModel(
              taxonId =
                  record[GBIF_TAXA.TAXON_ID]
                      ?: throw IllegalArgumentException("Taxon ID must be non-null"),
              scientificName =
                  record[GBIF_NAMES.NAME]
                      ?: throw IllegalArgumentException("Scientific name must be non-null"),
              familyName =
                  record[GBIF_TAXA.FAMILY]
                      ?: throw IllegalArgumentException("Family name must be non-null"),
              vernacularNames = record[vernacularNamesMultiset] ?: emptyList(),
              threatStatus = record[GBIF_DISTRIBUTIONS.THREAT_STATUS],
          )
        }
  }

  /**
   * Checks a scientific name for validity. If it's not valid, returns the details of the problem,
   * possibly including a suggested fix.
   *
   * Attempts to suggest a properly-spelled name that is regarded as the accepted name for the
   * species.
   *
   * @param [name] Scientific name to look up.
   * @return A [SpeciesProblemsRow] with the [SpeciesProblemsRow.fieldId],
   *   [SpeciesProblemsRow.typeId], and [SpeciesProblemsRow.suggestedValue] fields populated.
   */
  fun checkScientificName(name: String): SpeciesProblemsRow? {
    // Queries will be joining gbif_names against itself, so need to distinguish the two instances.
    val gbifNames2 = GBIF_NAMES.`as`("gbif_names_2")

    // Exact matches are faster than fuzzy ones, so be optimistic and see if the user already
    // spelled the name correctly. This should be the case pretty often given that we support
    // autocomplete in the UI.
    val exactMatch =
        dslContext
            .select(gbifNames2.NAME)
            .from(GBIF_NAMES)
            .join(GBIF_TAXA)
            .on(GBIF_NAMES.TAXON_ID.eq(GBIF_TAXA.TAXON_ID))
            .leftJoin(gbifNames2)
            .on(GBIF_TAXA.ACCEPTED_NAME_USAGE_ID.eq(gbifNames2.TAXON_ID))
            .and(gbifNames2.IS_SCIENTIFIC)
            .where(GBIF_NAMES.IS_SCIENTIFIC)
            .and(GBIF_NAMES.NAME.eq(name))
            .orderBy(
                // Since we rewrite scientific names to remove things like publication dates, we can
                // end up having two identical names in gbif_names that map to different taxon IDs.
                // For purposes of figuring out if the name is already valid, prefer the taxon whose
                // name is considered accepted.
                DSL.case_()
                    .`when`(GBIF_TAXA.TAXONOMIC_STATUS.eq(TAXONOMIC_STATUS_ACCEPTED), 1)
                    .else_(2),
                GBIF_NAMES.TAXON_ID,
            )
            .limit(1)
            .fetchOne()

    if (exactMatch != null) {
      // If the name is obsolete, return the accepted name; otherwise the name is correct.
      return exactMatch[gbifNames2.NAME]?.let { acceptedName ->
        SpeciesProblemsRow(
            fieldId = SpeciesProblemField.ScientificName,
            typeId = SpeciesProblemType.NameIsSynonym,
            suggestedValue = acceptedName,
        )
      }
    }

    val names =
        dslContext
            .select(gbifNames2.NAME, GBIF_NAMES.NAME)
            .from(GBIF_NAMES)
            .join(GBIF_TAXA)
            .on(GBIF_NAMES.TAXON_ID.eq(GBIF_TAXA.TAXON_ID))
            .leftJoin(gbifNames2)
            .on(GBIF_TAXA.ACCEPTED_NAME_USAGE_ID.eq(gbifNames2.TAXON_ID))
            .and(gbifNames2.IS_SCIENTIFIC)
            .where(GBIF_NAMES.IS_SCIENTIFIC)
            .and(GBIF_NAMES.NAME.likeFuzzy(name))
            .orderBy(GBIF_NAMES.NAME.similarity(name).desc())
            .limit(1)
            .fetchOne()

    return if (names != null) {
      val (acceptedName, correctedName) = names
      if (acceptedName != null) {
        SpeciesProblemsRow(
            fieldId = SpeciesProblemField.ScientificName,
            typeId = SpeciesProblemType.NameIsSynonym,
            suggestedValue = acceptedName,
        )
      } else {
        SpeciesProblemsRow(
            fieldId = SpeciesProblemField.ScientificName,
            typeId = SpeciesProblemType.NameMisspelled,
            suggestedValue = correctedName,
        )
      }
    } else {
      SpeciesProblemsRow(
          fieldId = SpeciesProblemField.ScientificName,
          typeId = SpeciesProblemType.NameNotFound,
      )
    }
  }

  companion object {
    const val TAXONOMIC_STATUS_ACCEPTED = "accepted"
  }
}
