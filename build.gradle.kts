import com.terraformation.gradle.computeGitVersion
import java.nio.file.Files
import java.util.Properties
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.internal.deprecation.DeprecatableConfiguration
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val jacksonVersion = "2.11.3"
val jooqVersion = "3.14.4"
val postgresJdbcVersion = "42.2.18"
val springDocVersion = "1.5.3"

plugins {
  val kotlinVersion = "1.4.30"

  kotlin("jvm") version kotlinVersion
  kotlin("kapt") version kotlinVersion
  kotlin("plugin.allopen") version kotlinVersion
  kotlin("plugin.spring") version kotlinVersion

  id("ch.ayedo.jooqmodelator") version "3.8.0"
  id("com.diffplug.spotless") version "5.8.2"
  id("org.springframework.boot") version "2.4.1"
  id("io.spring.dependency-management") version "1.0.10.RELEASE"

  // Add the build target to generate Swagger docs
  id("com.github.johnrengelman.processes") version "0.5.0"
  id("org.springdoc.openapi-gradle-plugin") version "1.3.0"
}

group = "com.terraformation"
version = computeGitVersion("0.1")
java.targetCompatibility = JavaVersion.VERSION_11

repositories {
  mavenCentral()
}

dependencies {
  jooqModelatorRuntime("org.postgresql:postgresql:$postgresJdbcVersion")

  kapt("org.springframework.boot:spring-boot-configuration-processor")

  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.boot:spring-boot-starter-jooq")
  implementation("org.springframework.boot:spring-boot-starter-security")
  implementation("org.springframework.boot:spring-boot-starter-validation")

  implementation("com.fasterxml.jackson:jackson-bom:$jacksonVersion")
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
  implementation("com.nimbusds:nimbus-jose-jwt:9.4.1")
  implementation("com.opencsv:opencsv:5.3")
  implementation("io.swagger.core.v3:swagger-annotations:2.1.6")
  implementation("javax.inject:javax.inject:1")
  implementation("org.codehaus.janino:janino:3.1.2")
  implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.0")
  implementation("org.flywaydb:flyway-core:7.5.0")
  implementation(kotlin("reflect"))
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactive:1.4.2")
  implementation("org.postgresql:postgresql:$postgresJdbcVersion")

  implementation("org.springdoc:springdoc-openapi-kotlin:$springDocVersion")
  implementation("org.springdoc:springdoc-openapi-security:$springDocVersion")
  implementation("org.springdoc:springdoc-openapi-ui:$springDocVersion")

  testImplementation("io.mockk:mockk:1.10.3-jdk8")
  testImplementation("org.junit.jupiter:junit-jupiter-api")
  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("org.springframework.security:spring-security-test")
  testImplementation(platform("org.testcontainers:testcontainers-bom:1.15.1"))
  testImplementation("org.testcontainers:junit-jupiter")
  testImplementation("org.testcontainers:postgresql")

  testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.7.0")

  developmentOnly("org.springframework.boot:spring-boot-devtools")
}

tasks.register("downloadDependencies") {
  fun ConfigurationContainer.resolveAll() = this
    .filter {
      it.isCanBeResolved &&
          (it !is DeprecatableConfiguration || it.resolutionAlternatives == null) &&
          !it.name.contains("Metadata")
    }
    .forEach { it.resolve() }

  doLast {
    configurations.resolveAll()
    buildscript.configurations.resolveAll()
  }
}

tasks.test {
  useJUnitPlatform()
  testLogging { exceptionFormat = TestExceptionFormat.FULL }
}

val preprocessJooqConfig by tasks.registering {
  val generatedConfigPath = File("$projectDir/src/generated/jooq-config.xml").toPath()

  inputs.file("$projectDir/jooq-codegen.xml")
  outputs.file(generatedConfigPath)

  doLast {
    val xml = File("$projectDir/jooq-codegen.xml")
      .readText()
      .replace("\$projectDir", projectDir.absolutePath)
    Files.createDirectories(generatedConfigPath.parent)
    Files.writeString(generatedConfigPath, xml)
  }
}

val generateVersionFile by tasks.registering {
  val generatedPath =
      File("$projectDir/src/generated/kotlin/com/terraformation/seedbank/Version.kt").toPath()

  inputs.property("version", project.version)
  outputs.file(generatedPath)

  doLast {
    Files.createDirectories(generatedPath.parent)
    Files.writeString(generatedPath,
        """package com.terraformation.seedbank
          |const val VERSION = "$version"
          |""".trimMargin()
    )
  }
}

tasks.withType<ch.ayedo.jooqmodelator.gradle.JooqModelatorTask> {
  dependsOn(preprocessJooqConfig)
}

jooqModelator {
  jooqVersion = "3.14.4"
  jooqEdition = "OSS"
  jooqConfigPath = preprocessJooqConfig.get().outputs.files.asPath
  jooqOutputPath = "$projectDir/src/generated/jooq"
  migrationEngine = "FLYWAY"
  migrationsPaths = listOf(
      "src/main/resources/db/migration/dev",
      "src/main/resources/db/migration/postgres",
      "src/main/resources/db/migration/common")
  dockerTag = "postgres:12"
  dockerEnv = listOf(
    "POSTGRES_DB=seedbank-build",
    "POSTGRES_USER=seedbank",
    "POSTGRES_PASSWORD=seedbank")
  dockerHostPort = 15432
  dockerContainerPort = 5432
}

sourceSets.main {
  java.srcDir("src/generated/jooq")
  java.srcDir("src/generated/kotlin")
}

tasks.withType<KotlinCompile> {
  dependsOn(tasks.withType<ch.ayedo.jooqmodelator.gradle.JooqModelatorTask>())
  dependsOn(generateVersionFile)
  kotlinOptions.jvmTarget = java.targetCompatibility.majorVersion
}

spotless {
  kotlin {
    ktfmt("0.20")
    targetExclude("src/generated/**")
  }
}

openApi {
  val properties = Properties()

  // Run the server on a port that's unlikely to already be in use.
  val listenPort = 32109
  properties["server.port"] = "$listenPort"
  apiDocsUrl.set("http://localhost:$listenPort/v3/api-docs.yaml")

  // Use application-apidoc.yaml for application configuration.
  properties["spring.profiles.active"] = "apidoc"

  outputDir.set(projectDir)
  outputFileName.set("openapi.yaml")
  forkProperties.set(properties)
}
