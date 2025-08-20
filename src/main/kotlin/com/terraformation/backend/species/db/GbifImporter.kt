package com.terraformation.backend.species.db

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.LockService
import com.terraformation.backend.db.LockType
import com.terraformation.backend.db.OperationInProgressException
import com.terraformation.backend.db.default_schema.GbifTaxonId
import com.terraformation.backend.db.default_schema.tables.records.GbifDistributionsRecord
import com.terraformation.backend.db.default_schema.tables.records.GbifNameWordsRecord
import com.terraformation.backend.db.default_schema.tables.records.GbifTaxaRecord
import com.terraformation.backend.db.default_schema.tables.records.GbifVernacularNamesRecord
import com.terraformation.backend.db.default_schema.tables.references.GBIF_DISTRIBUTIONS
import com.terraformation.backend.db.default_schema.tables.references.GBIF_NAMES
import com.terraformation.backend.db.default_schema.tables.references.GBIF_NAME_WORDS
import com.terraformation.backend.db.default_schema.tables.references.GBIF_TAXA
import com.terraformation.backend.db.default_schema.tables.references.GBIF_VERNACULAR_NAMES
import com.terraformation.backend.file.FileStore
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.util.ParsedCsvReader
import com.terraformation.backend.util.appendPath
import com.terraformation.backend.util.onChunk
import com.terraformation.backend.util.removeDiacritics
import jakarta.inject.Named
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.URI
import java.util.zip.ZipFile
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL

/**
 * Imports species information from GBIF backbone data files.
 *
 * The location of the files is passed in as a URL. If the URL scheme is `s3`, the files are assumed
 * to live in the same S3 bucket where we store photos, and the [FileStore] is used to access them.
 *
 * It reads three of the tab-separated-value text files from the backbone data distribution:
 * `Distribution.tsv`, `Taxon.tsv`, and `VernacularName.tsv`.
 *
 * There are some differences between the GBIF structure and what we want:
 * - Our list of species is flat, with a subspecies or variety treated as a separate species rather
 *   than a child of another species. In GBIF, there is a tree structure where a subspecies is
 *   considered a child of its species.
 * - We don't care about genus, but we do care which family each species is in (bearing in mind the
 *   flat definition of "species" per the previous point.) In the GBIF data, this relationship may
 *   be several parent/child relationships away (e.g., variety to species to genus to family).
 * - The GBIF "scientific name" includes authorship information we don't want. But the GBIF
 *   "canonical name" doesn't include connector words (`var.`, `subsp.`, `f.`) which we _do_ want.
 *   There isn't a GBIF name field that is always in the format we want.
 *
 * This populates a number of tables:
 * - [GBIF_DISTRIBUTIONS]: raw data from the `Distribution.tsv` file.
 * - [GBIF_TAXA]: raw data from the `Taxon.tsv` file.
 * - [GBIF_VERNACULAR_NAMES]: raw data from the `VernacularName.tsv` file.
 * - [GBIF_NAMES]: taxon names including the scientific name in the format we want, as well as all
 *   the vernacular names from [GBIF_VERNACULAR_NAMES].
 * - [GBIF_NAME_WORDS]: individual words from all the names in [GBIF_NAMES], folded to lower case,
 *   to support fast searching of word prefixes in typeaheads.
 */
@Named
class GbifImporter(
    private val config: TerrawareServerConfig,
    private val dslContext: DSLContext,
    private val fileStore: FileStore,
    private val lockService: LockService,
) {
  companion object {
    /** Number of rows to insert in a single SQL statement. */
    private const val INSERT_BATCH_SIZE = 50000

    private const val DISTRIBUTION_FILENAME = "Distribution.tsv"
    private const val TAXON_FILENAME = "Taxon.tsv"
    private const val VERNACULAR_NAMES_FILENAME = "VernacularName.tsv"

    /**
     * Taxon ranks whose names should be searchable. The names of ranks other than these are not
     * inserted into [GBIF_NAMES] or [GBIF_NAME_WORDS].
     */
    private val SEARCHABLE_TAXON_RANKS = setOf("species", "subspecies", "variety", "form")

    /**
     * All the tables affected by the import process. The order is important here: it needs to start
     * at the leaf nodes of the tree of foreign key relationships (that is, child tables first)
     * since the bulk delete at the start of the import is done in the order listed here.
     */
    private val GBIF_TABLES =
        listOf(
            GBIF_NAME_WORDS,
            GBIF_NAMES,
            GBIF_VERNACULAR_NAMES,
            GBIF_DISTRIBUTIONS,
            GBIF_TAXA,
        )
  }

  private val log = perClassLogger()

  fun import(prefix: URI) {
    import { filename -> openUri(prefix.appendPath(filename)) }
  }

  fun import(zipFile: ZipFile) {
    import { fileName ->
      val entry = zipFile.getEntry("backbone/$fileName") ?: throw FileNotFoundException(fileName)
      zipFile.getInputStream(entry)
    }
  }

  fun import(openFile: (String) -> InputStream) {
    requirePermissions { importGlobalSpeciesData() }

    try {
      dslContext.transaction { _ ->
        if (!lockService.tryExclusiveTransactional(LockType.GBIF_IMPORT)) {
          throw OperationInProgressException("Another import is currently in progress.")
        }

        deleteAll()

        val taxonIds = openFile(TAXON_FILENAME).use { inputStream -> importTaxa(inputStream) }

        openFile(DISTRIBUTION_FILENAME).use { inputStream ->
          importDistributions(inputStream, taxonIds)
        }

        openFile(VERNACULAR_NAMES_FILENAME).use { inputStream ->
          importVernacularNames(inputStream, taxonIds)
        }

        insertWords()
        analyzeAll()
      }
    } catch (e: DataAccessException) {
      log.error("GBIF import aborted; changes have been rolled back.", e.cause ?: e)
      throw e.cause ?: e
    }
  }

  private fun deleteAll() {
    log.info("Deleting existing GBIF data")

    GBIF_TABLES.forEach { table ->
      log.debug("Deleting from ${table.name}")
      dslContext.deleteFrom(table).execute()
    }
  }

  /**
   * Analyzes all the tables after everything has been imported. This is needed because the
   * PostgreSQL autovacuum daemon doesn't always analyze tables after big bulk inserts, and it can
   * generate very inefficient query plans if it doesn't have accurate statistics for the various
   * tables.
   */
  private fun analyzeAll() {
    log.info("Analyzing GBIF tables")

    GBIF_TABLES.forEach { table ->
      log.debug("Analyzing ${table.name}")
      dslContext.query("ANALYZE ${table.name}").execute()
    }
  }

  private fun importTaxa(inputStream: InputStream): Set<GbifTaxonId> {
    log.info("Importing GBIF taxon data")

    var count = 0

    val datasetIds =
        config.gbif.datasetIds?.toSet() ?: throw IllegalStateException("No dataset IDs configured")

    // This call chain is a little gross, but it's structured this way because we need to do
    // different things with different subsets of the data, and we don't want to have to load
    // the entire dataset into memory at once (the TSV file is over 2 gigabytes).

    return TaxaRecordParser(inputStream)
        .sequence()
        // We only care about taxa that are from specific sources.
        .filter { it.datasetId in datasetIds }
        // Bulk-insert a bunch of rows; this is vastly faster than inserting one row at a time.
        // onChunk is a helper method in our code, not a Kotlin standard library method.
        .onChunk(INSERT_BATCH_SIZE) { chunk ->
          dslContext
              .loadInto(GBIF_TAXA)
              .bulkAll()
              .commitNone()
              .loadRecords(chunk)
              .fieldsCorresponding()
              .execute()

          count += chunk.size
          log.debug("Imported $count taxa")
        }
        // We want the taxon entries for classes, orders, and such so we can easily do ad-hoc
        // analysis that takes the taxonomic tree structure into account, but we don't need their
        // names to be searchable. Filter the list down to just the taxon types whose names we need
        // to be able to search quickly.
        .filter { it.taxonRank in SEARCHABLE_TAXON_RANKS }
        .onChunk(INSERT_BATCH_SIZE) { chunk ->
          // Here we use insertInto() instead of the jOOQ loader API because the loader API
          // doesn't play super well with autogenerated primary keys.
          val rows = chunk.map { DSL.row(it.taxonId, computeScientificName(it), true) }
          dslContext
              .insertInto(
                  GBIF_NAMES,
                  GBIF_NAMES.TAXON_ID,
                  GBIF_NAMES.NAME,
                  GBIF_NAMES.IS_SCIENTIFIC,
              )
              .valuesOfRows(rows)
              .execute()
        }
        // Finally, return the IDs of the taxa whose names need to be searchable so that when we
        // import the other files, we can filter out irrelevant ones.
        .mapNotNull { it.taxonId }
        .toSet()
  }

  private fun importVernacularNames(inputStream: InputStream, taxonIds: Set<GbifTaxonId>) {
    log.info("Importing GBIF vernacular names")

    var count = 0

    VernacularNameRecordParser(inputStream)
        .sequence()
        // Only import names of taxa that we actually care about.
        .filter { it.taxonId in taxonIds }
        .onChunk(INSERT_BATCH_SIZE) { chunk ->
          dslContext
              .loadInto(GBIF_VERNACULAR_NAMES)
              .bulkAll()
              .commitNone()
              .loadRecords(chunk)
              .fieldsCorresponding()
              .execute()
        }
        .map { DSL.row(it.taxonId, it.vernacularName, it.language, false) }
        .chunked(INSERT_BATCH_SIZE)
        .forEach { chunk ->
          dslContext
              .insertInto(
                  GBIF_NAMES,
                  GBIF_NAMES.TAXON_ID,
                  GBIF_NAMES.NAME,
                  GBIF_NAMES.LANGUAGE,
                  GBIF_NAMES.IS_SCIENTIFIC,
              )
              .valuesOfRows(chunk)
              .execute()

          count += chunk.size
          log.debug("Imported $count vernacular names")
        }
  }

  private fun importDistributions(inputStream: InputStream, taxonIds: Set<GbifTaxonId>) {
    log.info("Importing GBIF distribution data")

    var count = 0

    DistributionsRecordParser(inputStream, config)
        .sequence()
        .filter { it.taxonId in taxonIds }
        .filter {
          it.establishmentMeans != null || it.occurrenceStatus != null || it.threatStatus != null
        }
        .chunked(INSERT_BATCH_SIZE)
        .forEach { chunk ->
          dslContext
              .loadInto(GBIF_DISTRIBUTIONS)
              .bulkAll()
              .commitNone()
              .loadRecords(chunk)
              .fieldsCorresponding()
              .execute()

          count += chunk.size
          log.debug("Imported $count distribution records")
        }
  }

  private fun insertWords() {
    var count = 0

    val scientificNameWords: Sequence<GbifNameWordsRecord> =
        dslContext
            .select(
                GBIF_NAMES.ID,
                GBIF_TAXA.GENERIC_NAME,
                GBIF_TAXA.SPECIFIC_EPITHET,
                GBIF_TAXA.INFRASPECIFIC_EPITHET,
            )
            .from(GBIF_NAMES)
            .join(GBIF_TAXA)
            .on(GBIF_NAMES.TAXON_ID.eq(GBIF_TAXA.TAXON_ID))
            .and(GBIF_NAMES.IS_SCIENTIFIC.isTrue)
            .fetchSize(5000)
            .fetchLazy()
            .asSequence()
            .flatMap { (nameId, genericName, specificEpithet, infraspecificEpithet) ->
              listOfNotNull(
                      genericName,
                      specificEpithet,
                      infraspecificEpithet,
                  )
                  .map { word -> GbifNameWordsRecord(nameId, word.removeDiacritics().lowercase()) }
            }

    val vernacularNameWords: Sequence<GbifNameWordsRecord> =
        dslContext
            .select(GBIF_NAMES.ID, GBIF_NAMES.NAME)
            .from(GBIF_NAMES)
            .where(GBIF_NAMES.IS_SCIENTIFIC.isFalse)
            .fetchSize(5000)
            .fetchLazy()
            .asSequence()
            .flatMap { (nameId, name) ->
              name!!
                  .split(' ')
                  .filter { it.length > 1 }
                  .map { word -> GbifNameWordsRecord(nameId, word.removeDiacritics().lowercase()) }
            }

    (scientificNameWords + vernacularNameWords).chunked(INSERT_BATCH_SIZE).forEach { records ->
      dslContext
          .loadInto(GBIF_NAME_WORDS)
          .bulkAll()
          .commitNone()
          .loadRecords(records)
          .fieldsCorresponding()
          .execute()
      count += records.size
      log.debug("Inserted $count words")
    }
  }

  private fun openUri(uri: URI): InputStream {
    return if (uri.scheme == "s3") {
      fileStore.read(uri)
    } else {
      uri.toURL().openStream()
    }
  }

  /**
   * Constructs a scientific name to present to users. This differs from the various names in the
   * GBIF data: the `scientificName` field includes additional attribution information and the
   * `canonicalName` field doesn't include a connecting word when there is an infraspecific epithet.
   */
  private fun computeScientificName(record: GbifTaxaRecord): String {
    return with(record) {
      when (taxonRank) {
        "subspecies" -> "$genericName $specificEpithet subsp. $infraspecificEpithet"
        "variety" -> "$genericName $specificEpithet var. $infraspecificEpithet"
        "form" -> "$genericName $specificEpithet f. $infraspecificEpithet"
        "species" -> "$genericName $specificEpithet"
        else -> scientificName ?: throw IllegalArgumentException("Record had no scientific name")
      }
    }
  }

  class TaxaRecordParser(inputStream: InputStream) :
      ParsedCsvReader<GbifTaxaRecord>(inputStream, tsvParser()) {
    override fun parseRow(row: Array<String?>): GbifTaxaRecord? {
      return if (row["kingdom"] == "Plantae") {
        GbifTaxaRecord(
            taxonId = row["taxonID"]?.let { GbifTaxonId(it) },
            datasetId = row["datasetID"],
            parentNameUsageId = row["parentNameUsageID"]?.let { GbifTaxonId(it) },
            acceptedNameUsageId = row["acceptedNameUsageID"]?.let { GbifTaxonId(it) },
            originalNameUsageId = row["originalNameUsageID"]?.let { GbifTaxonId(it) },
            scientificName = row["scientificName"],
            canonicalName = row["canonicalName"],
            genericName = row["genericName"],
            specificEpithet = row["specificEpithet"],
            infraspecificEpithet = row["infraspecificEpithet"],
            taxonRank = row["taxonRank"],
            taxonomicStatus = row["taxonomicStatus"],
            nomenclaturalStatus = row["nomenclaturalStatus"],
            phylum = row["phylum"],
            `class` = row["class"],
            order = row["order"],
            family = row["family"],
            genus = row["genus"],
        )
      } else {
        null
      }
    }
  }

  class VernacularNameRecordParser(inputStream: InputStream) :
      ParsedCsvReader<GbifVernacularNamesRecord>(inputStream, tsvParser()) {
    override fun parseRow(row: Array<String?>): GbifVernacularNamesRecord {
      return GbifVernacularNamesRecord(
          taxonId = row["taxonID"]?.let { GbifTaxonId(it) },
          vernacularName = row["vernacularName"],
          language = row["language"],
          countryCode = row["countryCode"],
      )
    }
  }

  class DistributionsRecordParser(inputStream: InputStream, config: TerrawareServerConfig) :
      ParsedCsvReader<GbifDistributionsRecord>(inputStream, tsvParser()) {
    private val sources: Set<String> = config.gbif.distributionSources?.toSet() ?: emptySet()

    override fun parseRow(row: Array<String?>): GbifDistributionsRecord? {
      return if (row["source"] in sources) {
        GbifDistributionsRecord(
            taxonId = row["taxonID"]?.let { GbifTaxonId(it) },
            countryCode = row["countryCode"],
            establishmentMeans = row["establishmentMeans"],
            occurrenceStatus = row["occurrenceStatus"],
            threatStatus = row["threatStatus"],
        )
      } else {
        null
      }
    }
  }
}
