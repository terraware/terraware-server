package com.terraformation.backend.jooq

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Dummy plugin to allow this subproject to be loaded into Gradle. Nothing actually uses this, but
 * putting it in the `plugins` list of the application's `build.gradle.kts` makes
 * [TerrawareGenerator] accessible to the build process.
 */
class JooqPlugin : Plugin<Project> {
  override fun apply(target: Project) {}
}
