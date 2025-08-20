package com.terraformation.backend.species.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.LockService
import com.terraformation.backend.db.LockType
import com.terraformation.backend.db.OperationInProgressException
import com.terraformation.backend.db.default_schema.GbifNameId
import com.terraformation.backend.db.default_schema.GbifTaxonId
import com.terraformation.backend.db.default_schema.tables.pojos.GbifDistributionsRow
import com.terraformation.backend.db.default_schema.tables.pojos.GbifNameWordsRow
import com.terraformation.backend.db.default_schema.tables.pojos.GbifNamesRow
import com.terraformation.backend.db.default_schema.tables.pojos.GbifTaxaRow
import com.terraformation.backend.db.default_schema.tables.pojos.GbifVernacularNamesRow
import com.terraformation.backend.db.default_schema.tables.records.GbifDistributionsRecord
import com.terraformation.backend.db.default_schema.tables.records.GbifTaxaRecord
import com.terraformation.backend.db.default_schema.tables.records.GbifVernacularNamesRecord
import com.terraformation.backend.db.default_schema.tables.references.GBIF_DISTRIBUTIONS
import com.terraformation.backend.db.default_schema.tables.references.GBIF_NAMES
import com.terraformation.backend.db.default_schema.tables.references.GBIF_NAME_WORDS
import com.terraformation.backend.db.default_schema.tables.references.GBIF_TAXA
import com.terraformation.backend.db.default_schema.tables.references.GBIF_VERNACULAR_NAMES
import com.terraformation.backend.file.FileStore
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.mockUser
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.net.URI
import java.nio.file.NoSuchFileException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class GbifImporterTest : DatabaseTest(), RunsAsUser {
  private val gbifConfig: TerrawareServerConfig.GbifConfig = mockk()
  private val config: TerrawareServerConfig = mockk()
  private val fileStore: FileStore = mockk()
  private val lockService: LockService = mockk()
  private lateinit var importer: GbifImporter

  override val user: TerrawareUser = mockUser()

  @BeforeEach
  fun setUp() {
    importer = GbifImporter(config, dslContext, fileStore, lockService)

    every { config.gbif } returns gbifConfig
    every { gbifConfig.datasetIds } returns listOf("dataset1", "dataset2", "dataset3")
    every { gbifConfig.distributionSources } returns listOf("source1", "source2")
    every { lockService.tryExclusiveTransactional(any()) } returns true
    every { user.canImportGlobalSpeciesData() } returns true
  }

  @Test
  fun `parses and imports data from files`() {
    val prefix = javaClass.getResource("/species/gbif")!!.toURI()
    importer.import(prefix)

    val expected =
        GbifData(
            listOf(
                GbifTaxaRow(
                    taxonId = GbifTaxonId(10),
                    datasetId = "dataset1",
                    parentNameUsageId = GbifTaxonId(9),
                    acceptedNameUsageId = GbifTaxonId(8),
                    originalNameUsageId = GbifTaxonId(7),
                    scientificName = "Family",
                    canonicalName = "Family!",
                    genericName = "Family?",
                    specificEpithet = "SpecificFamily",
                    infraspecificEpithet = "InfraFamily",
                    taxonRank = "family",
                    taxonomicStatus = "accepted",
                    nomenclaturalStatus = null,
                    phylum = "Phylum",
                    `class` = "Class",
                    order = "Order",
                    family = "Family",
                    genus = null,
                ),
                GbifTaxaRow(
                    taxonId = GbifTaxonId(11),
                    datasetId = "dataset2",
                    parentNameUsageId = GbifTaxonId(10),
                    acceptedNameUsageId = null,
                    originalNameUsageId = null,
                    scientificName = "Genus",
                    canonicalName = "Genus!",
                    genericName = "Genus?",
                    specificEpithet = "SpecificGenus",
                    infraspecificEpithet = "InfraGenus",
                    taxonRank = "genus",
                    taxonomicStatus = "accepted",
                    nomenclaturalStatus = null,
                    phylum = "Phylum",
                    `class` = "Class",
                    order = "Order",
                    family = "Family",
                    genus = "Genus",
                ),
                GbifTaxaRow(
                    taxonId = GbifTaxonId(12),
                    datasetId = "dataset3",
                    parentNameUsageId = GbifTaxonId(11),
                    acceptedNameUsageId = null,
                    originalNameUsageId = null,
                    scientificName = "Species",
                    canonicalName = "Species!",
                    genericName = "Species?",
                    specificEpithet = "SpecificSpecies",
                    infraspecificEpithet = "InfraSpecies",
                    taxonRank = "subspecies",
                    taxonomicStatus = "accepted",
                    nomenclaturalStatus = "nomStatus",
                    phylum = "Phylum",
                    `class` = "Class",
                    order = "Order",
                    family = "Family",
                    genus = "Genus",
                ),
            ),
            listOf(
                GbifDistributionsRow(
                    taxonId = GbifTaxonId(12),
                    countryCode = "CA",
                    establishmentMeans = "native",
                    occurrenceStatus = "present",
                    threatStatus = null,
                ),
                GbifDistributionsRow(
                    taxonId = GbifTaxonId(12),
                    countryCode = null,
                    establishmentMeans = null,
                    occurrenceStatus = null,
                    threatStatus = "least concern",
                ),
            ),
            listOf(
                GbifVernacularNamesRow(
                    taxonId = GbifTaxonId(12),
                    vernacularName = "My Species",
                    language = "en",
                    countryCode = "US",
                ),
                GbifVernacularNamesRow(taxonId = GbifTaxonId(12), vernacularName = "My Spécies 2"),
            ),
            listOf(
                GbifNamesRow(
                    taxonId = GbifTaxonId(12),
                    name = "Species? SpecificSpecies subsp. InfraSpecies",
                    language = null,
                    isScientific = true,
                ),
                GbifNamesRow(
                    taxonId = GbifTaxonId(12),
                    name = "My Species",
                    language = "en",
                    isScientific = false,
                ),
                GbifNamesRow(
                    taxonId = GbifTaxonId(12),
                    name = "My Spécies 2",
                    language = null,
                    isScientific = false,
                ),
            ),
            listOf(
                // See actualData doc for notes on how name IDs work here.
                GbifNameWordsRow(gbifNameId = GbifNameId(1), word = "infraspecies"),
                GbifNameWordsRow(gbifNameId = GbifNameId(1), word = "species?"),
                GbifNameWordsRow(gbifNameId = GbifNameId(1), word = "specificspecies"),
                GbifNameWordsRow(gbifNameId = GbifNameId(2), word = "my"),
                GbifNameWordsRow(gbifNameId = GbifNameId(2), word = "species"),
                GbifNameWordsRow(gbifNameId = GbifNameId(3), word = "my"),
                GbifNameWordsRow(gbifNameId = GbifNameId(3), word = "species"),
            ),
        )

    assertEquals(expected, actualData())
  }

  @Test
  fun `throws exception if data files not found`() {
    assertThrows<NoSuchFileException> {
      every { fileStore.read(any()) } throws NoSuchFileException("error")
      importer.import(URI("s3://bucket"))
    }
  }

  @Test
  fun `throws exception if no permission to import data`() {
    assertThrows<AccessDeniedException> {
      every { user.canImportGlobalSpeciesData() } returns false
      importer.import(URI("s3://bucket"))
    }
  }

  @Test
  fun `throws exception if another import is in progress`() {
    assertThrows<OperationInProgressException> {
      every { lockService.tryExclusiveTransactional(LockType.GBIF_IMPORT) } returns false
      importer.import(URI("s3://bucket"))
    }
  }

  @Test
  fun `replaces previous data with new data`() {
    dslContext
        .insertInto(GBIF_TAXA)
        .set(GBIF_TAXA.TAXON_ID, GbifTaxonId(1))
        .set(GBIF_TAXA.SCIENTIFIC_NAME, "name")
        .set(GBIF_TAXA.TAXON_RANK, "species")
        .set(GBIF_TAXA.TAXONOMIC_STATUS, "accepted")
        .execute()
    dslContext
        .insertInto(GBIF_VERNACULAR_NAMES)
        .set(GBIF_VERNACULAR_NAMES.TAXON_ID, GbifTaxonId(1))
        .set(GBIF_VERNACULAR_NAMES.VERNACULAR_NAME, "vernacular")
        .execute()
    dslContext
        .insertInto(GBIF_DISTRIBUTIONS)
        .set(GBIF_DISTRIBUTIONS.TAXON_ID, GbifTaxonId(1))
        .set(GBIF_DISTRIBUTIONS.THREAT_STATUS, "endangered")
        .execute()
    val nameId =
        dslContext
            .insertInto(GBIF_NAMES)
            .set(GBIF_NAMES.TAXON_ID, GbifTaxonId(1))
            .set(GBIF_NAMES.IS_SCIENTIFIC, true)
            .set(GBIF_NAMES.NAME, "name")
            .returning(GBIF_NAMES.ID)
            .fetchOne(GBIF_NAMES.ID)
    dslContext
        .insertInto(GBIF_NAME_WORDS)
        .set(GBIF_NAME_WORDS.GBIF_NAME_ID, nameId)
        .set(GBIF_NAME_WORDS.WORD, "word")
        .execute()

    // Make the FileStore return valid header rows with no data rows.
    val headerLines =
        mapOf(
            URI("s3://bucket/Distribution.tsv") to
                "taxonID\tlocationID\tlocality\tcountry\tcountryCode\tlocationRemarks\testablishmentMeans\tlifeStage\toccurrenceStatus\tthreatStatus\tsource\n",
            URI("s3://bucket/Taxon.tsv") to
                "taxonID\tdatasetID\tparentNameUsageID\tacceptedNameUsageID\toriginalNameUsageID\tscientificName\tscientificNameAuthorship\tcanonicalName\tgenericName\tspecificEpithet\tinfraspecificEpithet\ttaxonRank\tnameAccordingTo\tnamePublishedIn\ttaxonomicStatus\tnomenclaturalStatus\ttaxonRemarks\tkingdom\tphylum\tclass\torder\tfamily\tgenus\n",
            URI("s3://bucket/VernacularName.tsv") to
                "taxonID\tvernacularName\tlanguage\tcountry\tcountryCode\tsex\tlifeStage\tsource\n",
        )

    val uriSlot: CapturingSlot<URI> = slot()
    every { fileStore.read(capture(uriSlot)) } answers
        {
          val content = headerLines[uriSlot.captured]!!.encodeToByteArray()
          SizedInputStream(content.inputStream(), content.size.toLong())
        }

    importer.import(URI("s3://bucket"))

    val expected = GbifData(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    assertEquals(expected, actualData())
  }

  @Nested
  inner class DistributionRecordParserTest {
    private fun parse(input: String): List<GbifDistributionsRecord> {
      return GbifImporter.DistributionsRecordParser(input.byteInputStream(), config)
          .sequence()
          .toList()
    }

    @Test
    fun `throws exception if header does not contain required column`() {
      assertThrows<IllegalArgumentException> { parse("dummy\n1\n") }
    }

    @Test
    fun `ignores rows with wrong number of columns`() {
      val input =
          "taxonID\tlocationID\tlocality\tcountry\tcountryCode\tlocationRemarks\testablishmentMeans\tlifeStage\toccurrenceStatus\tthreatStatus\tsource\n" +
              "1\textra\t\tl\tCanada\tCA\t\tnative\t\tpresent\t\ts\n" +
              "1\tl\tCanada\tCA\t\tnative\t\tpresent\t\tsource1\n"
      assertEquals(emptyList<Any>(), parse(input))
    }

    @Test
    fun `throws exception on non-numeric taxon ID`() {
      val input =
          "taxonID\tlocationID\tlocality\tcountry\tcountryCode\tlocationRemarks\testablishmentMeans\tlifeStage\toccurrenceStatus\tthreatStatus\tsource\n" +
              "X\t\tl\tCanada\tCA\t\tnative\t\tpresent\t\tsource1\n"

      assertThrows<NumberFormatException> { parse(input) }
    }
  }

  @Nested
  inner class TaxaRecordParserTest {
    private fun parse(input: String): List<GbifTaxaRecord> {
      return GbifImporter.TaxaRecordParser(input.byteInputStream()).sequence().toList()
    }

    @Test
    fun `throws exception if header does not contain required column`() {
      assertThrows<IllegalArgumentException> { parse("dummy\n1\n") }
    }

    @Test
    fun `ignores rows with wrong number of columns`() {
      val input =
          "taxonID\tdatasetID\tparentNameUsageID\tacceptedNameUsageID\toriginalNameUsageID\tscientificName\tscientificNameAuthorship\tcanonicalName\tgenericName\tspecificEpithet\tinfraspecificEpithet\ttaxonRank\tnameAccordingTo\tnamePublishedIn\ttaxonomicStatus\tnomenclaturalStatus\ttaxonRemarks\tkingdom\tphylum\tclass\torder\tfamily\tgenus\n" +
              "1\textra\tdataset3\t\t\t\tSpecies\tAuthor3\tSpecies!\tSpecies?\tSpecificSpecies\tInfraSpecies\tsubspecies\tAccord3\tPublish3\taccepted\tnomStatus\tRemarks3\tPlantae\tPhylum\tClass\tOrder\tFamily\tGenus\n" +
              "1\t\t\t\tSpecies\tAuthor3\tSpecies!\tSpecies?\tSpecificSpecies\tInfraSpecies\tsubspecies\tAccord3\tPublish3\taccepted\tnomStatus\tRemarks3\tPlantae\tPhylum\tClass\tOrder\tFamily\tGenus\n"

      assertEquals(emptyList<Any>(), parse(input))
    }

    @Test
    fun `ignores rows with non-plant kingdoms`() {
      val input =
          "taxonID\tdatasetID\tparentNameUsageID\tacceptedNameUsageID\toriginalNameUsageID\tscientificName\tscientificNameAuthorship\tcanonicalName\tgenericName\tspecificEpithet\tinfraspecificEpithet\ttaxonRank\tnameAccordingTo\tnamePublishedIn\ttaxonomicStatus\tnomenclaturalStatus\ttaxonRemarks\tkingdom\tphylum\tclass\torder\tfamily\tgenus\n" +
              "1\tdataset3\t\t\t\tSpecies\tAuthor3\tSpecies!\tSpecies?\tSpecificSpecies\tInfraSpecies\tsubspecies\tAccord3\tPublish3\taccepted\tnomStatus\tRemarks3\tAnimalia\tPhylum\tClass\tOrder\tFamily\tGenus\n"

      assertEquals(emptyList<Any>(), parse(input))
    }

    @Test
    fun `throws exception on non-numeric taxon ID`() {
      val input =
          "taxonID\tdatasetID\tparentNameUsageID\tacceptedNameUsageID\toriginalNameUsageID\tscientificName\tscientificNameAuthorship\tcanonicalName\tgenericName\tspecificEpithet\tinfraspecificEpithet\ttaxonRank\tnameAccordingTo\tnamePublishedIn\ttaxonomicStatus\tnomenclaturalStatus\ttaxonRemarks\tkingdom\tphylum\tclass\torder\tfamily\tgenus\n" +
              "X\tdataset3\t\t\t\tSpecies\tAuthor3\tSpecies!\tSpecies?\tSpecificSpecies\tInfraSpecies\tsubspecies\tAccord3\tPublish3\taccepted\tnomStatus\tRemarks3\tPlantae\tPhylum\tClass\tOrder\tFamily\tGenus\n"

      assertThrows<NumberFormatException> { parse(input) }
    }
  }

  @Nested
  inner class VernacularNameRecordParserTest {
    private fun parse(input: String): List<GbifVernacularNamesRecord> {
      return GbifImporter.VernacularNameRecordParser(input.byteInputStream()).sequence().toList()
    }

    @Test
    fun `throws exception if header does not contain required column`() {
      assertThrows<IllegalArgumentException> { parse("dummy\n1\n") }
    }

    @Test
    fun `ignores rows with wrong number of columns`() {
      val input =
          "taxonID\tvernacularName\tlanguage\tcountry\tcountryCode\tsex\tlifeStage\tsource\n" +
              "1\tx\ten\tx\tUS\tx\tx\n"

      assertEquals(emptyList<Any>(), parse(input))
    }

    @Test
    fun `throws exception on non-numeric taxon ID`() {
      val input =
          "taxonID\tvernacularName\tlanguage\tcountry\tcountryCode\tsex\tlifeStage\tsource\n" +
              "X\tx\ten\tx\tUS\tM\tx\tx\n"

      assertThrows<NumberFormatException> { parse(input) }
    }
  }

  /**
   * Bundles all the data together into a single object so that the entire imported data set can be
   * asserted all at once. If we did individual assertions on the contents of each table, an
   * assertion failure would cause the test method to abort before the remaining tables could be
   * evaluated.
   */
  data class GbifData(
      val taxa: List<GbifTaxaRow>,
      val distributions: List<GbifDistributionsRow>,
      val vernacularNames: List<GbifVernacularNamesRow>,
      val names: List<GbifNamesRow>,
      val nameWords: List<GbifNameWordsRow>,
  )

  /**
   * Returns the actual data from the database. This is mostly a straight dump of the table
   * contents, with the exception of name IDs.
   *
   * In [GbifData.names], the name IDs are set to null, but the rows are still sorted by the
   * original IDs.
   *
   * In [GbifData.nameWords], the name IDs are set to the ordinal position of the name ID in the
   * list of the actual IDs. That is, if the actual inserted name IDs are 46, 47, and 55,
   * [GbifData.nameWords] will have `GbifNameId(1)` instead of 46, `GbifNameId(2)` instead of 47,
   * and `GbifNameId(3)` instead of 55. This allows tests to assert that name words are associated
   * with the correct names.
   */
  private fun actualData(): GbifData {
    val taxa =
        dslContext
            .selectFrom(GBIF_TAXA)
            .orderBy(GBIF_TAXA.TAXON_ID)
            .fetchInto(GbifTaxaRow::class.java)

    val distributions =
        dslContext
            .selectFrom(GBIF_DISTRIBUTIONS)
            .orderBy(GBIF_DISTRIBUTIONS.TAXON_ID, GBIF_DISTRIBUTIONS.COUNTRY_CODE)
            .fetchInto(GbifDistributionsRow::class.java)

    val vernacularNames =
        dslContext
            .selectFrom(GBIF_VERNACULAR_NAMES)
            .orderBy(GBIF_VERNACULAR_NAMES.TAXON_ID, GBIF_VERNACULAR_NAMES.VERNACULAR_NAME)
            .fetchInto(GbifVernacularNamesRow::class.java)

    val names =
        dslContext.selectFrom(GBIF_NAMES).orderBy(GBIF_NAMES.ID).fetchInto(GbifNamesRow::class.java)
    val nameIdMap = names.mapIndexed { index, row -> row.id to GbifNameId(index + 1L) }.toMap()

    val nameWords =
        dslContext
            .selectFrom(GBIF_NAME_WORDS)
            .orderBy(GBIF_NAME_WORDS.GBIF_NAME_ID, GBIF_NAME_WORDS.WORD)
            .fetchInto(GbifNameWordsRow::class.java)

    return GbifData(
        taxa,
        distributions,
        vernacularNames,
        names.map { it.copy(id = null) },
        nameWords.map { it.copy(gbifNameId = nameIdMap[it.gbifNameId]) },
    )
  }
}
