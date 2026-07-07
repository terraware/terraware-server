package com.terraformation.backend.species

import com.opencsv.CSVParser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.ExternalDatasetType
import com.terraformation.backend.db.default_schema.SpeciesNativity
import com.terraformation.backend.db.default_schema.WcvpTaxonId
import com.terraformation.backend.db.default_schema.tables.records.WcvpDistributionsRecord
import com.terraformation.backend.db.default_schema.tables.records.WcvpTaxaRecord
import com.terraformation.backend.db.default_schema.tables.references.EXTERNAL_DATASET_IMPORTS
import com.terraformation.backend.db.default_schema.tables.references.WCVP_DISTRIBUTIONS
import com.terraformation.backend.db.default_schema.tables.references.WCVP_TAXA
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.species.event.WcvpImportedEvent
import com.terraformation.backend.util.ParsedCsvReader
import com.terraformation.backend.util.onChunk
import jakarta.inject.Named
import java.io.InputStream
import java.net.URI
import java.time.InstantSource
import java.util.zip.ZipFile
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.context.ApplicationEventPublisher

/**
 * Imports the World Checklist of Vascular plants data.
 *
 * Note that the JUnit test for this class is skipped by default; set the `TEST_WCVP_IMPORTER`
 * environment variable to enable it if you're updating this code.
 */
@Named
class WcvpImporter(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
) {
  companion object {
    val defaultZipFileUrl = URI("https://sftp.kew.org/pub/data-repositories/WCVP/wcvp_dwca.zip")

    /** Number of rows to insert in a single SQL statement. */
    private const val INSERT_BATCH_SIZE = 50000

    private const val DISTRIBUTION_FILENAME = "wcvp_distribution.csv"
    private const val TAXON_FILENAME = "wcvp_taxon.csv"
  }

  private val log = perClassLogger()

  fun import(zipFile: ZipFile) {
    requirePermissions { importGlobalSpeciesData() }

    val dcReader = DarwinCoreReader(zipFile)

    dslContext.transaction { _ ->
      dslContext.truncate(WCVP_DISTRIBUTIONS, WCVP_TAXA).execute()

      val taxonIds = importTaxa(dcReader)
      importDistributions(dcReader, taxonIds)

      updateImportTime(dcReader)

      eventPublisher.publishEvent(WcvpImportedEvent())
    }
  }

  private fun importTaxa(dcReader: DarwinCoreReader): Set<WcvpTaxonId> {
    var count = 0

    log.info("Importing WCVP taxon data")

    return dcReader
        .parseFile("Taxon", ::TaxonParser)
        .onChunk(INSERT_BATCH_SIZE) { chunk ->
          dslContext
              .loadInto(WCVP_TAXA)
              .bulkAll()
              .commitNone()
              .loadRecords(chunk)
              .fieldsCorresponding()
              .execute()

          count += chunk.size
          log.debug("Imported $count taxa")
        }
        .mapNotNull { it.taxonId }
        .toSet()
  }

  private fun importDistributions(dcReader: DarwinCoreReader, taxonIds: Set<WcvpTaxonId>) {
    log.info("Importing WCVP distribution data")

    var count = 0

    dcReader
        .parseFile("Distribution", ::DistributionParser)
        .filter { it.taxonId in taxonIds }
        .chunked(INSERT_BATCH_SIZE)
        .forEach { chunk ->
          dslContext
              .loadInto(WCVP_DISTRIBUTIONS)
              .bulkAll()
              .commitNone()
              .loadRecords(chunk)
              .fieldsCorresponding()
              .execute()

          count += chunk.size
          log.debug("Imported $count distribution records")
        }
  }

  private fun updateImportTime(dcReader: DarwinCoreReader) {
    with(EXTERNAL_DATASET_IMPORTS) {
      dslContext
          .insertInto(EXTERNAL_DATASET_IMPORTS)
          .set(EXTERNAL_DATASET_TYPE_ID, ExternalDatasetType.WCVP)
          .set(IMPORTED_TIME, clock.instant())
          .set(LAST_PUBLICATION_DATE, dcReader.publicationDate)
          .onConflict(EXTERNAL_DATASET_TYPE_ID)
          .doUpdate()
          .set(IMPORTED_TIME, DSL.excluded(IMPORTED_TIME))
          .set(LAST_PUBLICATION_DATE, DSL.excluded(LAST_PUBLICATION_DATE))
          .execute()
    }
  }

  private class DistributionParser(
      inputStream: InputStream,
      csvParser: CSVParser,
      columnNames: List<String>,
  ) : ParsedCsvReader<WcvpDistributionsRecord>(inputStream, csvParser, columnNames) {
    // Some distributions have location IDs like "TDWG:84" which aren't valid level 3 codes.
    private val tdwgPattern = Regex("^TDWG:[A-Z]+")

    override fun parseRow(row: Array<String?>): WcvpDistributionsRecord? {
      val locationId = row["locationID"] ?: return null
      val taxonId = row["id"]?.let { WcvpTaxonId(it) } ?: return null

      val nativity =
          when {
            row["occurrenceStatus"] == null &&
                row["threatStatus"] == null &&
                row["establishmentMeans"] == null -> SpeciesNativity.Native
            row["establishmentMeans"] == "introduced" -> SpeciesNativity.Introduced
            else -> SpeciesNativity.Unknown
          }

      return if (tdwgPattern.matches(locationId)) {
        WcvpDistributionsRecord(
            establishmentMeans = row["establishmentMeans"],
            level3Code = locationId.substringAfter(':'),
            occurrenceStatus = row["occurrenceStatus"],
            speciesNativityId = nativity,
            taxonId = taxonId,
            threatStatus = row["threatStatus"],
        )
      } else {
        null
      }
    }
  }

  private class TaxonParser(
      inputStream: InputStream,
      csvParser: CSVParser,
      columnNames: List<String>,
  ) : ParsedCsvReader<WcvpTaxaRecord>(inputStream, csvParser, columnNames) {
    private val excludedTaxonomicStatuses =
        setOf("Illegitimate", "Invalid", "Misapplied", "Synonym")

    override fun parseRow(row: Array<String?>): WcvpTaxaRecord? {
      val taxonRank = row["taxonRank"] ?: return null
      val taxonomicStatus = row["taxonomicStatus"]

      if (taxonomicStatus in excludedTaxonomicStatuses) {
        return null
      }

      return WcvpTaxaRecord(
          acceptedNameUsageId = row["acceptedNameUsageID"]?.let { WcvpTaxonId(it) },
          family = row["family"],
          genus = row["genus"],
          infraspecificEpithet = row["infraSpecificEpithet"],
          nomenclaturalStatus = row["nomenclaturalStatus"],
          originalNameUsageId = row["originalNameUsageID"]?.let { WcvpTaxonId(it) },
          parentNameUsageId = row["parentNameUsageID"]?.let { WcvpTaxonId(it) },
          scientificName = row["scientificName"],
          specificEpithet = row["specificEpithet"],
          taxonId = row["id"]?.let { WcvpTaxonId(it) },
          taxonomicStatus = taxonomicStatus,
          taxonRank = taxonRank,
      )
    }
  }
}
