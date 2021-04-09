import java.util.Properties

plugins {
  `kotlin-dsl`
}

repositories {
  mavenCentral()
}

// buildSrc is considered a separate project from the parent, so we don't have access to properties
// from the parent by default. Load the parent's properties explicitly so we can define dependency
// versions in one place.
val rootProjectProperties =
    projectDir.parentFile.resolve("gradle.properties").inputStream().use { stream ->
      Properties().apply { load(stream) }
    }

dependencies {
  val jooqVersion = rootProjectProperties["jooqVersion"]

  implementation("org.jooq:jooq-codegen:$jooqVersion")
  implementation("org.eclipse.jgit:org.eclipse.jgit:5.10.+")
}
