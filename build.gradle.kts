import java.nio.file.Files
import org.gradle.internal.deprecation.DeprecatableConfiguration
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val jacksonVersion = "2.11.3"
val jooqVersion = "3.14.4"
val micronautVersion = "2.2.2"
val postgresJdbcVersion = "42.2.18"

plugins {
  val kotlinVersion = "1.4.255-SNAPSHOT"

  kotlin("jvm") version kotlinVersion
  kotlin("kapt") version kotlinVersion
  kotlin("plugin.allopen") version kotlinVersion

  id("ch.ayedo.jooqmodelator") version "3.8.0"
  id("com.diffplug.spotless") version "5.8.2"
  id("io.micronaut.application") version "1.2.0"
}

group = "com.terraformation"
version = "0.1-SNAPSHOT"
java.targetCompatibility = JavaVersion.VERSION_11

repositories {
  mavenCentral()
  maven {
    url = uri("https://oss.sonatype.org/content/repositories/snapshots/")
  }
}

dependencies {
  annotationProcessor("io.micronaut.security:micronaut-security-annotations")
  kapt("io.micronaut.openapi:micronaut-openapi")

  jooqModelatorRuntime("org.postgresql:postgresql:$postgresJdbcVersion")

  implementation("com.fasterxml.jackson:jackson-bom:$jacksonVersion")
  implementation("io.micronaut.flyway:micronaut-flyway")
  implementation("io.micronaut.kotlin:micronaut-kotlin-runtime")
  implementation("io.micronaut.security:micronaut-security")
  implementation("io.micronaut.security:micronaut-security-annotations")
  implementation("io.micronaut.security:micronaut-security-jwt")
  implementation("io.micronaut.sql:micronaut-jdbc-hikari")
  implementation("io.micronaut.sql:micronaut-jooq")
  implementation("io.swagger.core.v3:swagger-annotations")
  implementation("org.codehaus.janino:janino:3.1.2")
  implementation(kotlin("reflect"))
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactive:1.4.2")
  implementation("org.postgresql:postgresql:$postgresJdbcVersion")

  testAnnotationProcessor("io.micronaut:micronaut-inject-java")

  testImplementation("io.micronaut.test:micronaut-test-junit5")
  testImplementation("io.mockk:mockk:1.10.3-jdk8")
  testImplementation("org.junit.jupiter:junit-jupiter-api")

  testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.7.0")
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
}

micronaut {
  version(micronautVersion)
  runtime("netty")
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
}

tasks.withType<KotlinCompile> {
  dependsOn(tasks.withType<ch.ayedo.jooqmodelator.gradle.JooqModelatorTask>())
  kotlinOptions.jvmTarget = java.targetCompatibility.majorVersion
}

application {
  mainClass.set("com.terraformation.seedbank.Application")
}

spotless {
  kotlin {
    ktfmt("0.19")
    targetExclude("src/generated/**")
  }
}
