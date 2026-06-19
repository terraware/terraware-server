package com.terraformation.backend.species

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.opencsv.CSVParser
import com.terraformation.backend.util.ParsedCsvReader
import java.io.FileNotFoundException
import java.io.InputStream
import java.time.LocalDate
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Reads Darwin Core Archive zipfiles. These zipfiles are assumed to contain a master metadata file
 * called `meta.xml` which describes the other files in the zipfile. One of the other files is a
 * second XML file (typically `eml.xml`) that has information about the dataset.
 */
class DarwinCoreReader(private val zipFile: ZipFile) {
  private val archive: Archive by lazy { readXmlFromZipFile<Archive>("meta.xml") }
  private val dataset: Dataset by lazy { readXmlFromZipFile<Eml>(archive.metadata).dataset }

  private val countryCodesByName: Map<String, String> by lazy {
    javaClass.getResourceAsStream("/i18n/Countries_en.properties").use { inputStream ->
      val countryProps = Properties()
      countryProps.load(inputStream)
      countryProps.map { "${it.value}" to "${it.key}" }.toMap()
    }
  }

  val publicationDate: LocalDate
    get() = dataset.pubDate

  val title: String
    get() = dataset.title

  /**
   * Returns this archive's alpha-2 country code. This is nearly always specified using a keyword in
   * the archive's metadata, sometimes with a hyphenated country subdivision suffix. But a few
   * resources don't define that keyword, or don't use a valid country code; for those, we try to
   * infer the country code by assuming that the English country name is part of the archive's
   * title.
   */
  val countryCode: String by lazy {
    val countryFromKeyword =
        dataset.keywordSet
            .firstOrNull { it.keyword.startsWith("country_", ignoreCase = true) }
            ?.keyword
            ?.substringAfter('_')
            ?.substringBefore('-')

    val countryCodeFromKeyword =
        if (countryFromKeyword?.length == 2) {
          countryFromKeyword.uppercase()
        } else {
          null
        }

    // Fall back to title if keyword doesn't have an alpha-2 code. Titles, at least in the GRIIS
    // data, usually look like one of:
    //
    // <prefix> - Country Name
    // <prefix> - Region Name, Country Name

    countryCodeFromKeyword
        ?: if (" - " in title) {
          val titleCountryName = title.substringAfterLast(" - ")
          countryCodesByName[titleCountryName]
              ?: countryCodesByName[titleCountryName.substringAfterLast(',').trim()]
              ?: throw IllegalArgumentException(
                  "Unable to look up country code for country $titleCountryName"
              )
        } else {
          throw IllegalArgumentException("No country code found")
        }
  }

  /** Returns a parsed CSV stream of the file that contains rows of a particular type. */
  fun <T> parseFile(
      rowType: String,
      createReader: (InputStream, CSVParser, List<String>) -> ParsedCsvReader<T>,
  ): Sequence<T> {
    val fileDetails =
        if (rowType == "Taxon") {
          archive.core
        } else {
          archive.extensions.firstOrNull { it.rowType.endsWith("/$rowType") }
        }
    if (fileDetails == null) {
      throw FileNotFoundException("Archive does not contain file with row type $rowType")
    }

    val idField = fileDetails.coreId ?: fileDetails.id
    val columnNames =
        (listOfNotNull(idField) + fileDetails.fields)
            .sortedBy { it.index }
            .map { it.term?.substringAfterLast('/') ?: "id" }

    val fileName = fileDetails.files.location
    val zipFileEntry = getEntry(fileName)

    return sequence {
      zipFile.getInputStream(zipFileEntry).use { inputStream ->
        val reader =
            createReader(
                inputStream,
                ParsedCsvReader.separatorParser(fileDetails.separatorChar),
                columnNames,
            )
        yieldAll(reader.sequence())
      }
    }
  }

  private inline fun <reified T> readXmlFromZipFile(fileName: String): T {
    val entry = getEntry(fileName)

    return zipFile.getInputStream(entry).use { inputStream -> xmlMapper.readValue<T>(inputStream) }
  }

  private fun getEntry(fileName: String): ZipEntry {
    return zipFile.getEntry(fileName)
        ?: throw FileNotFoundException("Archive does not contain the file $fileName")
  }

  @JacksonXmlRootElement(localName = "eml", namespace = "http://rs.tdwg.org/dwc/text/")
  data class Eml(val dataset: Dataset)

  data class Dataset(
      val title: String,
      val pubDate: LocalDate,
      val keywordSet: List<Keyword>,
  )

  data class Keyword(
      val keyword: String,
      val keywordThesaurus: String,
  )

  @JacksonXmlRootElement(localName = "archive", namespace = "http://rs.tdwg.org/dwc/text/")
  data class Archive(
      @JacksonXmlProperty(isAttribute = true) val metadata: String = "eml.xml",
      @JacksonXmlProperty(localName = "core") val core: FileDetails,
      @JacksonXmlProperty(localName = "extension") val extensions: List<FileDetails> = emptyList(),
  )

  data class FileDetails(
      @JacksonXmlProperty(isAttribute = true) val encoding: String = "UTF-8",
      @JacksonXmlProperty(isAttribute = true) val fieldsTerminatedBy: String = "\\t",
      @JacksonXmlProperty(isAttribute = true) val linesTerminatedBy: String = "\\n",
      @JacksonXmlProperty(isAttribute = true) val fieldsEnclosedBy: String = "",
      @JacksonXmlProperty(isAttribute = true) val ignoreHeaderLines: Int = 1,
      @JacksonXmlProperty(isAttribute = true) val rowType: String,
      @JacksonXmlProperty(localName = "files") val files: Files,
      @JacksonXmlProperty(localName = "id") val id: Field? = null,
      @JacksonXmlProperty(localName = "coreid") val coreId: Field? = null,
      @JacksonXmlProperty(localName = "field") val fields: List<Field> = emptyList(),
  ) {
    val separatorChar
      get() =
          when (fieldsTerminatedBy) {
            "\\t" -> '\t'
            else -> fieldsTerminatedBy[0]
          }
  }

  data class Files(val location: String)

  data class Field(
      @JacksonXmlProperty(isAttribute = true) val index: Int = 0,
      @JacksonXmlProperty(isAttribute = true) val term: String? = null,
  )

  companion object {
    private val xmlMapper: ObjectMapper by lazy {
      XmlMapper.builder()
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
          .defaultUseWrapper(false)
          .build()
          .registerKotlinModule()
          .registerModule(JavaTimeModule())
    }
  }
}
