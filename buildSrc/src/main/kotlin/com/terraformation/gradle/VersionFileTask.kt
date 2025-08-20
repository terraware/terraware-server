package com.terraformation.gradle

import java.nio.file.Files
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Generates a Kotlin source file that contains the project version number, so it can be referenced
 * at runtime.
 */
abstract class VersionFileTask : DefaultTask() {
  @get:Input val version: String = "${project.version}"

  @get:OutputFile
  val outputFile =
      project.layout.buildDirectory.file("generated/kotlin/com/terraformation/backend/Version.kt")

  @TaskAction
  fun generate() {
    val path = outputFile.get().asFile.toPath()
    Files.createDirectories(path.parent)
    Files.writeString(
        path,
        "package com.terraformation.backend\nconst val VERSION = \"$version\"\n",
    )
  }
}
