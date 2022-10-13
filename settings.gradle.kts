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

rootProject.name = "terraware-server"
