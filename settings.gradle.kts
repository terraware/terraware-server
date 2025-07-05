pluginManagement {
  repositories {
    mavenCentral()
    gradlePluginPortal()
  }

  resolutionStrategy {
    eachPlugin {
      if (requested.id.namespace?.startsWith("org.jetbrains.kotlin") == true) {
        useVersion(gradle.rootProject.property("kotlinVersion").toString())
      }
    }
  }

  includeBuild("jooq")
}

plugins {
  // Automatically downloads the correct JDK version if it's not installed locally.
  id("org.gradle.toolchains.foojay-resolver-convention") version ("1.0.0")
}

rootProject.name = "terraware-server"
