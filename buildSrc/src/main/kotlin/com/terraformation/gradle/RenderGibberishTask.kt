package com.terraformation.gradle

import com.github.gradle.node.util.ProjectApiHelper
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.Properties
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.FileType
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFiles
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import org.gradle.work.ChangeType
import org.gradle.work.InputChanges

/** Translates English messages into gibberish for localization testing. */
abstract class RenderGibberishTask : DefaultTask() {
  @get:IgnoreEmptyDirectories
  @get:InputFiles
  @get:SkipWhenEmpty
  val propertiesFiles =
      project.files(
          project.fileTree("src/main/resources/i18n") {
            include("**/*.properties")
            exclude("**/*_*.properties")
          })

  @get:OutputFiles val outputFiles = propertiesFiles.files.map { getTargetFile(it) }

  @get:Internal val projectHelper = ProjectApiHelper.newInstance(project)

  @get:Inject abstract val objects: ObjectFactory

  init {
    group = "build"
    description = "Renders gibberish strings."
  }

  @TaskAction
  fun exec(changes: InputChanges) {
    changes.getFileChanges(propertiesFiles).forEach { change ->
      if (change.fileType != FileType.DIRECTORY) {
        val targetFile = getTargetFile(change.file)

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
          "$english".split(' ').asReversed().joinToString(" ") { word ->
            if (word.startsWith('{')) {
              word
            } else {
              val bytes = word.toByteArray()
              Base64.getEncoder().encodeToString(bytes).trimEnd('=')
            }
          }
    }

    targetFile.writer().use { gibberishProperties.store(it, null) }
  }

  /**
   * Returns the target path for the given properties file in the gibberish locale. The upper levels
   * of directory structure are a little different in the src and build directories; we want the
   * following mapping:
   *
   * `src/main/resources/i18n/a/b.properties` -> `build/resources/main/i18n/a/b_gx.properties`
   */
  private fun getTargetFile(file: File): File {
    val extension = "properties"

    if (file.extension != extension) {
      throw IllegalArgumentException("File $file is not a properties file")
    }

    val targetFilename = file.nameWithoutExtension + "_gx.$extension"

    val targetRelativeToResourcesDir =
        file.parentFile
            .resolve(targetFilename)
            .relativeTo(project.projectDir.resolve("src/main/resources"))

    return project.buildDir.resolve("resources/main").resolve(targetRelativeToResourcesDir)
  }
}
