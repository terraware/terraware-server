package com.terraformation.backend.jooq

import java.io.File
import java.io.FileNotFoundException
import java.io.StringWriter
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

/** Wrapper around [Properties] that saves the properties in alphabetical order. */
internal class SortedPropertiesFile(schemaFile: File, baseName: String) {
  private val file = bundleFile(schemaFile, "$baseName.properties")
  private val properties: Properties by lazy {
    if (file.exists()) {
      file.reader().use { reader -> Properties().apply { load(reader) } }
    } else {
      Properties()
    }
  }

  fun deleteAllWithPrefix(prefix: String) {
    properties
        .propertyNames()
        .toList()
        .filter { "$it".startsWith(prefix) }
        .forEach { properties.remove(it) }
  }

  operator fun set(key: String, value: String) {
    properties[key] = value
  }

  fun save() {
    file.parentFile.mkdirs()
    file.writer().use { writer ->
      // Let java.util.Properties do the necessary escaping, but sort its output alphabetically.
      val stringWriter = StringWriter()
      properties.store(stringWriter, null)

      val sortedFileContents =
          stringWriter
              .toString()
              .split('\n')
              // Remove the mandatory comment lines Java generates; they'll sort incorrectly
              .filterNot { it.startsWith('#') }
              .sorted()
              .joinToString("\n")

      val formattedTime =
          DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC))
      writer.write("# GENERATED $formattedTime - DO NOT EDIT\n")
      writer.write(sortedFileContents)
    }
  }

  companion object {
    private tailrec fun File.findParentWithName(desiredName: String): File? {
      return when {
        name == desiredName -> this
        parentFile == null -> null
        else -> parentFile.findParentWithName(desiredName)
      }
    }

    private fun bundleFile(schemaFile: File, filename: String): File {
      // TODO: See if there's a better way to figure out this path
      val sourceRoot =
          schemaFile.findParentWithName("build")
              ?: throw FileNotFoundException("Can't find build parent directory")

      val bundleDirectory = sourceRoot.resolve("resources").resolve("main").resolve("i18n")
      bundleDirectory.mkdirs()

      return bundleDirectory.resolve(filename)
    }
  }
}
