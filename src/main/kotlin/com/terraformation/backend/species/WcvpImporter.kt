package com.terraformation.backend.species

import com.opencsv.CSVParser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.WcvpTaxonId
import com.terraformation.backend.db.default_schema.tables.records.WcvpDistributionsRecord
import com.terraformation.backend.db.default_schema.tables.records.WcvpTaxaRecord
import com.terraformation.backend.db.default_schema.tables.references.WCVP_DISTRIBUTIONS
import com.terraformation.backend.db.default_schema.tables.references.WCVP_TAXA
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.species.event.WcvpImportedEvent
import com.terraformation.backend.util.ParsedCsvReader
import com.terraformation.backend.util.onChunk
import jakarta.inject.Named
import java.io.InputStream
import java.net.URI
import java.util.zip.ZipFile
import org.jooq.DSLContext
import org.springframework.context.ApplicationEventPublisher

/**
 * Imports the World Checklist of Vascular plants data.
 *
 * Note that the JUnit test for this class is skipped by default; set the `TEST_WCVP_IMPORTER`
 * environment variable to enable it if you're updating this code.
 */
@Named
class WcvpImporter(
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
        .map { it.taxonId!! }
        .toSet()
  }

  private fun importDistributions(dcReader: DarwinCoreReader, taxonIds: Set<WcvpTaxonId>) {
    log.info("Importing WCVP distribution data")

    var count = 0
    val unknownTaxonIds = mutableListOf<WcvpTaxonId?>()

    dcReader
        .parseFile("Distribution", ::DistributionParser)
        .filter { row ->
          if (row.taxonId in taxonIds) {
            true
          } else {
            unknownTaxonIds.add(row.taxonId)
            false
          }
        }
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

    if (unknownTaxonIds.isNotEmpty()) {
      log.warn("Found taxon IDs in distributions that were not in taxon list: $unknownTaxonIds")
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

      return if (tdwgPattern.matches(locationId)) {
        WcvpDistributionsRecord(
            taxonId = taxonId,
            occurrenceStatus = row["occurrenceStatus"],
            threatStatus = row["threatStatus"],
            establishmentMeans = row["establishmentMeans"],
            level3Code = locationId.substringAfter(':'),
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
    override fun parseRow(row: Array<String?>): WcvpTaxaRecord? {
      val taxonRank = row["taxonRank"] ?: return null

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
          taxonomicStatus = row["taxonomicStatus"],
          taxonRank = taxonRank,
      )
    }
  }
}
