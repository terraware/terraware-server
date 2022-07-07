import java.util.Properties
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins { `kotlin-dsl` }

// Needed to avoid noisy warning message from Gradle. Won't be needed in Gradle 7.5.
// https://github.com/gradle/gradle/issues/18935 https://github.com/gradle/gradle/issues/19308
java.targetCompatibility = JavaVersion.VERSION_1_8

tasks.withType<KotlinCompile> { kotlinOptions.jvmTarget = java.targetCompatibility.majorVersion }

repositories {
  gradlePluginPortal()
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

  implementation("com.github.node-gradle:gradle-node-plugin:3.4.0")
  implementation("org.jooq:jooq-codegen:$jooqVersion")
  implementation("org.eclipse.jgit:org.eclipse.jgit:5.10.+")
}
