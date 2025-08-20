package com.terraformation.gradle

import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.Properties
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileType
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFile
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFiles
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskAction
import org.gradle.work.ChangeType
import org.gradle.work.InputChanges

/** Translates English messages into gibberish for localization testing. */
abstract class RenderGibberishTask
@Inject
constructor(
    layout: ProjectLayout,
    objectFactory: ObjectFactory,
    sourceSets: SourceSetContainer,
) : DefaultTask() {
  private val resourcesSourceDir =
      sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME).resources.srcDirs.first()
  private val resourcesBuildDir = layout.buildDirectory.dir("resources/main")

  @get:IgnoreEmptyDirectories
  @get:InputFiles
  @get:SkipWhenEmpty
  val propertiesFiles: ConfigurableFileCollection =
      objectFactory
          .fileCollection()
          .from(
              objectFactory
                  .fileTree()
                  .from(resourcesSourceDir.resolve("i18n"))
                  .include("**/*_en.properties")
          )

  @get:OutputFiles val outputFiles = propertiesFiles.files.map { getTargetFile(it) }

  init {
    group = "build"
    description = "Renders gibberish strings."
  }

  @TaskAction
  fun exec(changes: InputChanges) {
    changes.getFileChanges(propertiesFiles).forEach { change ->
      if (change.fileType != FileType.DIRECTORY) {
        val targetFile = getTargetFile(change.file).get().asFile

        if (change.changeType == ChangeType.REMOVED) {
          targetFile.delete()
        } else {
          renderGibberish(change.file, targetFile)
        }
      }
    }
  }

  private fun renderGibberish(englishFile: File, targetFile: File) {
    Files.createDirectories(targetFile.toPath().parent)

    val englishProperties = Properties()
    val gibberishProperties = Properties()

    englishFile.reader().use { englishProperties.load(it) }

    englishProperties.forEach { name, english ->
      gibberishProperties[name] =
          "$english".split('\n').joinToString("\n") { line ->
            line.split(' ').asReversed().joinToString(" ") { encodeWord(it) }
          }
    }

    targetFile.writer().use { gibberishProperties.store(it, null) }
  }

  /**
   * Encodes a word as gibberish, preserving template variables and link text.
   *
   * Square-bracket-delimited links in strings are a bit subtle because we reverse the word order in
   * gibberish. So we need to flip the link markers around if the link is multiple words:
   * - `a` -> `YQ`
   * - `b` -> `Yg`
   * - `[a]` -> `[YQ]` (square bracket prefix/suffix are retained on a single-word link)
   * - `a b` -> `Yg YQ` (note the order is reversed here: the gibberish "a" is at the end)
   * - `[a b]` -> `[Yg YQ]` (the `[` prefix on `[a` turns into a `]` suffix, and the `]` suffix on
   *   `b]` turns into a `[` prefix)
   */
  private fun encodeWord(word: String) =
      if (word.startsWith('{')) {
        word
      } else {
        val bytes = word.replace("[", "").replace("]", "").toByteArray()
        val gibberish = Base64.getEncoder().encodeToString(bytes).trimEnd('=')

        when {
          '[' in word && ']' in word -> "[$gibberish]"
          '[' in word -> "$gibberish]"
          ']' in word -> "[$gibberish"
          else -> gibberish
        }
      }

  /**
   * Returns the target path for the given properties file in the gibberish locale. The upper levels
   * of directory structure are a little different in the src and build directories; we want the
   * following mapping:
   *
   * `src/main/resources/i18n/a/b_en.properties` -> `build/resources/main/i18n/a/b_gx.properties`
   */
  private fun getTargetFile(file: File): Provider<RegularFile> {
    val extension = "properties"

    if (file.extension != extension) {
      throw IllegalArgumentException("File $file is not a properties file")
    }

    val targetFilename = file.name.replace("_en.$extension", "_gx.$extension")

    val targetRelativeToResourcesDir =
        file.parentFile.resolve(targetFilename).relativeTo(resourcesSourceDir)

    return resourcesBuildDir.map { it.file(targetRelativeToResourcesDir.toString()) }
  }
}
