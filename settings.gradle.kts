pluginManagement {
  repositories {
    mavenCentral()
    maven {
      url = uri("https://oss.sonatype.org/content/repositories/snapshots/")
    }
    gradlePluginPortal()
    jcenter()
  }
}

rootProject.name = "seedbank-server"
