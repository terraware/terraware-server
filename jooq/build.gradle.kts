import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  `java-gradle-plugin`
  `kotlin-dsl`
}

repositories {
  mavenCentral()
  gradlePluginPortal()
}

// This subproject is isolated from the parent project, but we want to use the same jOOQ version in
// both places. Explicitly load the parent's properties file so we can pull out the version number.
val rootProjectProperties =
    projectDir.parentFile.resolve("gradle.properties").inputStream().use { stream ->
      Properties().apply { load(stream) }
    }

dependencies {
  val jooqVersion = rootProjectProperties["jooqVersion"]

  api(gradleApi())
  api("org.jooq:jooq-codegen:$jooqVersion")
}

group = "com.terraformation"

version = "1"

gradlePlugin {
  plugins.register("jooq") {
    id = "terraware-jooq"
    implementationClass = "com.terraformation.backend.jooq.JooqPlugin"
  }
}

java { targetCompatibility = JavaVersion.VERSION_20 }

tasks.withType<KotlinCompile> {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_20)
    allWarningsAsErrors.set(true)
  }
}
