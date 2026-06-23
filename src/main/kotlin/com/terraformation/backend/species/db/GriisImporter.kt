package com.terraformation.backend.species.db

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.opencsv.CSVParser
import com.terraformation.backend.db.default_schema.tables.records.GriisTaxaRecord
import com.terraformation.backend.db.default_schema.tables.references.GRIIS_RESOURCES
import com.terraformation.backend.db.default_schema.tables.references.GRIIS_TAXA
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.species.DarwinCoreReader
import com.terraformation.backend.util.ParsedCsvReader
import com.terraformation.backend.util.UriFetcher
import jakarta.inject.Named
import jakarta.ws.rs.core.UriBuilder
import java.io.InputStream
import java.net.URI
import java.nio.file.Files.copy
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * Imports data from the Global Register of Introduced and Invasive Species.
 *
 * The GRIIS data comes in the form of hundreds of independently maintained datasets (resources) for
 * different regions. Regions are often entire countries, but are sometimes more fine-grained.
 *
 * Resources are named inconsistently, so callers should generally call [fetchResourceList] first to
 * get the list of available resource names and when each one was last updated.
 *
 * Resources don't use a completely consistent data format across all regions, though they are all
 * Darwin Core archives and all have at least a minimum set of core data fields.
 */
@Named
class GriisImporter(
    private val dslContext: DSLContext,
    private val objectMapper: ObjectMapper,
    private val uriFetcher: UriFetcher,
) {
  companion object {
    /**
     * Number of resources to request from the resource search service. This should be set high
     * enough to avoid having th navigate through paginated results. As of June 2026, there are 380
     * resources.
     */
    private const val RESOURCE_PAGE_SIZE = 1000

    /**
     * URL of resource search service. Length is set to 1000 to avoid having to navigate through
     * paginated results; as of June 2026 there are 380 resources, so 1000 should be plenty of
     * headroom.
     */
    val griisListResourcesUri =
        URI("https://cloud.gbif.org/griis/api/resources?start=0&length=$RESOURCE_PAGE_SIZE")

    /**
     * Base URL for fetching resources. The resource name is added to this using a query parameter.
     */
    val griisResourceUri = URI("https://cloud.gbif.org/griis/archive.do")

    /*
     * Column indexes of the data we care about from the resource list. The resource list is
     * returned as a JSON array of arrays of strings.
     */
    private const val RESOURCE_NAME_INDEX = 11
    private const val RESOURCE_UPDATED_TIME_INDEX = 6

    /** Format of timestamp columns in resource list. */
    private val timestampFormat =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)!!

    /**
     * Resources we don't want to import, either because they are irrelevant to us or because they
     * are incompatible with our import logic.
     */
    private val excludedResourceNames =
        setOf(
            // Marine region that is not associated with a specific country.
            "griisnorthsea",
        )
  }

  private val log = perClassLogger()

  fun fetchResourceList(): List<GriisResource> {
    val responsePayload =
        uriFetcher.openStream(griisListResourcesUri).use { inputStream ->
          objectMapper.readValue<ResourceListResponsePayload>(inputStream)
        }

    if (responsePayload.aaData.size >= RESOURCE_PAGE_SIZE) {
      log.error(
          "GRIIS resource list has grown past $RESOURCE_PAGE_SIZE resources; increase " +
              "fetch size or implement pagination"
      )
    }

    return responsePayload.aaData.mapNotNull { aaDataEntry ->
      val resourceName = aaDataEntry.getOrNull(RESOURCE_NAME_INDEX)

      if (resourceName != null && resourceName !in excludedResourceNames) {
        val updatedTime =
            aaDataEntry
                .getOrNull(RESOURCE_UPDATED_TIME_INDEX)
                ?.let { ZonedDateTime.parse(it, timestampFormat) }
                ?.toInstant() ?: Instant.EPOCH

        GriisResource(resourceName, updatedTime)
      } else {
        null
      }
    }
  }

  /**
   * Imports data from a resource if it has changed since the last time it was imported.
   *
   * @return true if the resource was actually imported, false if it was skipped.
   */
  fun importResource(
      resource: GriisResource,
      forceUpdate: Boolean = false,
  ): Boolean {
    return importResource(resource.name, resource.updatedTime, forceUpdate)
  }

  /**
   * Imports data from a resource if it has changed since the last time it was imported.
   *
   * @return true if the resource was actually imported, false if it was skipped.
   */
  fun importResource(
      resourceName: String,
      updatedTime: Instant,
      forceUpdate: Boolean = false,
  ): Boolean {
    if (!forceUpdate) {
      val currentUpdatedTime =
          dslContext.fetchValue(
              GRIIS_RESOURCES.UPDATED_TIME,
              GRIIS_RESOURCES.RESOURCE_NAME.eq(resourceName),
          )
      if (currentUpdatedTime != null && currentUpdatedTime >= updatedTime) {
        log.info("GRIIS resource $resourceName already up to date; skipping")
        return false
      }
    }

    val resourceUrl = UriBuilder.fromUri(griisResourceUri).queryParam("r", resourceName).build()
    val tempFile = createTempFile(suffix = ".zip")

    try {
      log.info("Copying $resourceUrl to local filesystem: $tempFile")

      uriFetcher.openStream(resourceUrl).use { fileStream ->
        copy(fileStream, tempFile, StandardCopyOption.REPLACE_EXISTING)
      }

      ZipFile(tempFile.toFile()).use { zipFile ->
        importResource(resourceName, updatedTime, zipFile)
      }
    } finally {
      tempFile.deleteIfExists()
    }

    return true
  }

  private fun importResource(resourceName: String, updatedTime: Instant, zipFile: ZipFile) {
    val dcReader = DarwinCoreReader(zipFile)

    log.info("Importing GRIIS taxon list from resource $resourceName: ${dcReader.title}")

    val speciesProfiles =
        dcReader.parseFile("SpeciesProfile", ::SpeciesProfileReader).associateBy { it.taxonId }
    val distributions =
        dcReader.parseFile("Distribution", ::DistributionReader).associateBy { it.taxonId }
    val taxa = dcReader.parseFile("Taxon", ::TaxonReader).toList()

    dslContext.transaction { _ ->
      val resourceId =
          with(GRIIS_RESOURCES) {
            dslContext
                .insertInto(GRIIS_RESOURCES)
                .set(RESOURCE_NAME, resourceName)
                .set(COUNTRY_CODE, dcReader.countryCode)
                .set(PUBLICATION_DATE, dcReader.publicationDate)
                .set(UPDATED_TIME, updatedTime)
                .onConflict(RESOURCE_NAME)
                .doUpdate()
                .set(COUNTRY_CODE, DSL.excluded(COUNTRY_CODE))
                .set(PUBLICATION_DATE, DSL.excluded(PUBLICATION_DATE))
                .set(UPDATED_TIME, DSL.excluded(UPDATED_TIME))
                .execute()

            dslContext.fetchValue(ID, RESOURCE_NAME.eq(resourceName))
          }

      with(GRIIS_TAXA) {
        val taxaRecords = taxa.map { taxon ->
          val distribution =
              distributions[taxon.taxonId]
                  ?: throw IllegalArgumentException(
                      "Taxon ID ${taxon.taxonId} not present in distributions list"
                  )
          val profile =
              speciesProfiles[taxon.taxonId]
                  ?: throw IllegalArgumentException(
                      "Taxon ID ${taxon.taxonId} not present in species profiles list"
                  )

          GriisTaxaRecord(
              acceptedNameUsage = taxon.acceptedNameUsage,
              establishmentMeans = distribution.establishmentMeans,
              griisResourceId = resourceId,
              habitat = profile.habitat,
              isInvasive = profile.isInvasive,
              occurrenceStatus = distribution.occurrenceStatus,
              scientificName = taxon.scientificName,
              taxonId = taxon.taxonId,
              taxonomicStatus = taxon.taxonomicStatus,
              taxonRank = taxon.taxonRank,
          )
        }

        dslContext.deleteFrom(GRIIS_TAXA).where(GRIIS_RESOURCE_ID.eq(resourceId)).execute()

        dslContext.batchInsert(taxaRecords).execute()

        log.info("Imported ${taxaRecords.size} GRIIS taxa from resource $resourceName")
      }
    }
  }

  data class GriisResource(
      val name: String,
      val updatedTime: Instant,
  )

  @JsonIgnoreProperties(ignoreUnknown = true)
  data class ResourceListResponsePayload(
      val aaData: List<List<String>>,
  )

  data class Distribution(
      val taxonId: Long,
      val establishmentMeans: String?,
      val occurrenceStatus: String?,
  )

  data class SpeciesProfile(
      val taxonId: Long,
      val habitat: String?,
      val isInvasive: Boolean,
  )

  data class Taxon(
      val taxonId: Long,
      val acceptedNameUsage: String?,
      val scientificName: String,
      val taxonRank: String,
      val taxonomicStatus: String,
  )

  class DistributionReader(
      inputStream: InputStream,
      csvParser: CSVParser,
      columnNames: List<String>,
  ) : ParsedCsvReader<Distribution>(inputStream, csvParser, columnNames) {
    override fun parseRow(row: Array<String?>): Distribution? {
      val taxonId = row["id"]?.toLongOrNull() ?: return null

      return Distribution(
          taxonId = taxonId,
          establishmentMeans = row["establishmentMeans"],
          occurrenceStatus = row.getOrNull("occurrenceStatus"),
      )
    }
  }

  class SpeciesProfileReader(
      inputStream: InputStream,
      csvParser: CSVParser,
      columnNames: List<String>,
  ) : ParsedCsvReader<SpeciesProfile>(inputStream, csvParser, columnNames) {
    override fun parseRow(row: Array<String?>): SpeciesProfile? {
      val taxonId = row["id"]?.toLongOrNull() ?: return null

      return SpeciesProfile(
          taxonId = taxonId,
          habitat = row.getOrNull("habitat"),
          isInvasive = row["isInvasive"] == "Invasive",
      )
    }
  }

  class TaxonReader(
      inputStream: InputStream,
      csvParser: CSVParser,
      columnNames: List<String>,
  ) : ParsedCsvReader<Taxon>(inputStream, csvParser, columnNames) {
    override fun parseRow(row: Array<String?>): Taxon? {
      // Taxon ID is supposed to be numeric, but sometimes resources incorrectly use URLs instead.
      val taxonId = row["id"]?.toLongOrNull() ?: row["id"]?.hashCode()?.toLong() ?: return null
      val taxonRank = row["taxonRank"]?.lowercase() ?: return null
      val taxonomicStatus = row.getOrNull("taxonomicStatus")?.lowercase() ?: "accepted"

      if (
          row["kingdom"] != "Plantae" ||
              taxonRank == "synonym" ||
              !taxonomicStatus.startsWith("accepted")
      ) {
        return null
      }

      return Taxon(
          taxonId = taxonId,
          acceptedNameUsage = row["acceptedNameUsage"],
          scientificName = row["scientificName"]!!,
          taxonRank = taxonRank,
          taxonomicStatus = taxonomicStatus,
      )
    }
  }
}
