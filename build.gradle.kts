import com.terraformation.gradle.computeGitVersion
import java.nio.file.Files
import java.util.Properties
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.internal.deprecation.DeprecatableConfiguration
import org.jetbrains.kotlin.gradle.internal.KaptGenerateStubsTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jooq.meta.jaxb.Strategy
import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
  kotlin("jvm")
  kotlin("plugin.allopen")
  kotlin("plugin.spring")

  // kapt is required to build the metadata for application.yaml autocomplete of our
  // config settings, but slows the build down significantly; disable it by default.
  // Uncomment the kapt line in the dependencies block if you enable this.
  // kotlin("kapt")

  id("com.revolut.jooq-docker") version "0.3.5"
  id("com.diffplug.spotless") version "5.10.2"
  id("org.springframework.boot") version "2.4.3"
  id("io.spring.dependency-management") version "1.0.11.RELEASE"

  // Add the build target to generate Swagger docs
  id("com.github.johnrengelman.processes") version "0.5.0"
  id("org.springdoc.openapi-gradle-plugin") version "1.3.2"
}

buildscript {
  // Force the jOOQ codegen plugin to use the same jOOQ version we use in the application code.
  val jooqVersion: String by project
  configurations.classpath {
    resolutionStrategy {
      setForcedModules("org.jooq:jooq-codegen:$jooqVersion")
    }
  }
}

group = "com.terraformation"

version = computeGitVersion("0.1")

java.targetCompatibility = JavaVersion.VERSION_15

repositories { mavenCentral() }

dependencies {
  val awsSdkVersion: String by project
  val jacksonVersion: String by project
  val postgresJdbcVersion: String by project
  val springDocVersion: String by project

  jdbc("org.postgresql:postgresql:$postgresJdbcVersion")

  // Build autocomplete metadata for our config settings in application.yaml. This
  // requires kapt which slows the build down significantly, so is commented out.
  // Uncomment the kotlin("kapt") line above if you enable this.
  // kapt("org.springframework.boot:spring-boot-configuration-processor")

  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.boot:spring-boot-starter-jooq")
  implementation("org.springframework.boot:spring-boot-starter-security")
  implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
  implementation("org.springframework.boot:spring-boot-starter-validation")

  implementation("com.fasterxml.jackson:jackson-bom:$jacksonVersion")
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
  implementation("com.opencsv:opencsv:5.3")
  implementation("io.swagger.core.v3:swagger-annotations:2.1.10")
  implementation("javax.inject:javax.inject:1")
  implementation("net.postgis:postgis-jdbc:2021.1.0")
  implementation("net.rakugakibox.spring.boot:logback-access-spring-boot-starter:2.7.1")
  implementation("org.apache.tika:tika-core:2.1.0")
  implementation("org.codehaus.janino:janino:3.1.3")
  implementation("org.flywaydb:flyway-core:7.5.4")
  implementation("org.keycloak:keycloak-admin-client:14.0.0")
  implementation(kotlin("reflect"))
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.4.2")
  implementation("org.postgresql:postgresql:$postgresJdbcVersion")
  implementation(platform("software.amazon.awssdk:bom:$awsSdkVersion"))
  implementation("software.amazon.awssdk:s3")

  implementation("org.springdoc:springdoc-openapi-kotlin:$springDocVersion")
  implementation("org.springdoc:springdoc-openapi-security:$springDocVersion")
  implementation("org.springdoc:springdoc-openapi-ui:$springDocVersion")

  runtimeOnly("net.logstash.logback:logstash-logback-encoder:6.6")

  testImplementation("io.mockk:mockk:1.10.6")
  testImplementation("org.junit.jupiter:junit-jupiter-api")
  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("org.springframework.security:spring-security-test")
  testImplementation(platform("org.testcontainers:testcontainers-bom:1.15.2"))
  testImplementation("org.testcontainers:junit-jupiter")
  testImplementation("org.testcontainers:postgresql")

  testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.7.1")

  developmentOnly("org.springframework.boot:spring-boot-devtools")
}

tasks.register("downloadDependencies") {
  fun ConfigurationContainer.resolveAll() =
      this
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

val generateVersionFile by tasks.registering {
  val generatedPath =
      File("$buildDir/generated/kotlin/com/terraformation/backend/Version.kt").toPath()

  inputs.property("version", project.version)
  outputs.file(generatedPath)

  doLast {
    Files.createDirectories(generatedPath.parent)
    Files.writeString(
        generatedPath,
        """package com.terraformation.backend
          |const val VERSION = "$version"
          |""".trimMargin())
  }
}

tasks {
  generateJooqClasses {
    basePackageName = "com.terraformation.backend.db"
    excludeFlywayTable = true
    schemas = arrayOf("public")

    customizeGenerator {
      val generator = com.terraformation.backend.jooq.TerrawareGenerator()
      val pluralStrategy = com.terraformation.backend.jooq.PluralPojoStrategy()

      name = generator.javaClass.name
      strategy = Strategy().withName(pluralStrategy.javaClass.name)
      database.apply {
        withName("org.jooq.meta.postgres.PostgresDatabase")
        withIncludes(".*")
        withExcludes(generator.excludes())
        withForcedTypes(generator.forcedTypes(basePackageName))
        // Fix compiler warnings for PostGIS functions; see https://github.com/jOOQ/jOOQ/issues/8587
        withTableValuedFunctions(false)
      }

      generate.apply {
        isDaos = true
        isJavaTimeTypes = true
        isPojos = true
        isPojosAsKotlinDataClasses = true
        isRecords = true
        isRoutines = false
      }
    }

    flywayProperties =
        mapOf(
            "flyway.locations" to
                listOf(
                        "src/main/resources/db/migration/dev",
                        "src/main/resources/db/migration/postgres",
                        "src/main/resources/db/migration/common")
                    .joinToString(",") { "filesystem:$projectDir/$it" })
  }
}

jooq {
  image {
    repository = "postgis/postgis"
    tag = "12-3.0"
  }
}

sourceSets.main {
  java.srcDir("build/generated/kotlin")
}

tasks.withType<KotlinCompile> {
  dependsOn(generateVersionFile)
  kotlinOptions.jvmTarget = java.targetCompatibility.majorVersion
}

tasks.withType<KaptGenerateStubsTask> {
  dependsOn(tasks.generateJooqClasses)
}

tasks.getByName<BootJar>("bootJar") {
  // Don't package local development settings in the distribution.
  exclude("application-dev*.yaml")
}

spotless {
  kotlin {
    ktfmt("0.28")
    targetExclude("build/**")
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
