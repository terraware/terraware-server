package com.terraformation.gradle

import java.nio.file.Files
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Generates a Kotlin source file that defines the Docker image to use for database-backed tests.
 * This defaults to a standard PostGIS image, but may be overridden locally to test against
 * different database versions or to use an image for a platform that isn't supported by the
 * standard PostGIS images, e.g., ARM64.
 */
abstract class PostgresDockerConfigTask : DefaultTask() {
  @get:Input
  val postgresDockerRepository: String = project.property("postgresDockerRepository")!!.toString()
  @get:Input val postgresDockerTag: String = project.property("postgresDockerTag")!!.toString()

  @get:OutputFile
  val outputFile =
      project.layout.buildDirectory.file(
          "generated-test/kotlin/com/terraformation/backend/db/DockerImage.kt"
      )

  @TaskAction
  fun generate() {
    val path = outputFile.get().asFile.toPath()
    Files.createDirectories(path.parent)
    Files.writeString(
        path,
        """package com.terraformation.backend.db
          |const val POSTGRES_DOCKER_REPOSITORY = "$postgresDockerRepository"
          |const val POSTGRES_DOCKER_TAG = "$postgresDockerTag"
        """
            .trimMargin(),
    )
  }
}
