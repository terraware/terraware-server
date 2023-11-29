import com.github.jk1.license.filter.LicenseBundleNormalizer
import com.github.jk1.license.render.InventoryHtmlReportRenderer
import com.terraformation.gradle.PostgresDockerConfigTask
import com.terraformation.gradle.VersionFileTask
import com.terraformation.gradle.computeGitVersion
import java.net.URI
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.internal.deprecation.DeprecatableConfiguration
import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.internal.KaptGenerateStubsTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jooq.meta.jaxb.Strategy
import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
  kotlin("jvm")
  kotlin("plugin.spring")
  `jvm-test-suite`

  // kapt is required to build the metadata for application.yaml autocomplete of our
  // config settings, but slows the build down significantly; disable it by default.
  // Uncomment the kapt line in the dependencies block if you enable this.
  // kotlin("kapt")

  id("dev.monosoul.jooq-docker") version "6.0.3"
  id("com.diffplug.spotless") version "6.19.0"
  id("org.jetbrains.dokka") version "1.9.0"
  id("org.springframework.boot") version "3.2.0"
  id("io.spring.dependency-management") version "1.1.4"

  // Add the build target to generate Swagger docs
  id("com.github.johnrengelman.processes") version "0.5.0"
  id("org.springdoc.openapi-gradle-plugin") version "1.8.0"

  id("com.github.jk1.dependency-license-report") version "2.1"

  id("terraware-jooq")
}

buildscript {
  // Force the jOOQ codegen plugin to use the same jOOQ version we use in the application code.
  val jooqVersion: String by project
  configurations.classpath {
    resolutionStrategy {
      setForcedModules(
          "org.jooq:jooq-codegen:$jooqVersion",
      )
    }
  }

  // The MJML -> HTML translator for email messages is a Node.js utility. This plugin is a
  // dependency of buildSrc, but we need it here as well so we can configure it.
  apply(plugin = "com.github.node-gradle.node")
}

group = "com.terraformation"

version = computeGitVersion("0.1")

repositories {
  maven("https://repo.osgeo.org/repository/geotools-releases/")
  mavenCentral()
}

dependencies {
  val awsSdkVersion: String by project
  val flywayVersion: String by project
  val geoToolsVersion: String by project
  val jooqVersion: String by project
  val jtsVersion: String by project
  val ktorVersion: String by project
  val postgresJdbcVersion: String by project
  val springDocVersion: String by project

  jooqCodegen("org.postgresql:postgresql:$postgresJdbcVersion")

  // Build autocomplete metadata for our config settings in application.yaml. This
  // requires kapt which slows the build down significantly, so is commented out.
  // Uncomment the kotlin("kapt") line above if you enable this.
  // kapt("org.springframework.boot:spring-boot-configuration-processor")

  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.boot:spring-boot-starter-jersey")
  implementation("org.springframework.boot:spring-boot-starter-jooq")
  implementation("org.springframework.boot:spring-boot-starter-mail")
  implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
  implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
  implementation("org.springframework.boot:spring-boot-starter-security")
  implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
  implementation("org.springframework.boot:spring-boot-starter-validation")
  implementation("org.springframework.boot:spring-boot-starter-webflux")
  implementation("org.springframework.session:spring-session-jdbc")

  implementation("com.drewnoakes:metadata-extractor:2.19.0")
  implementation("com.google.api-client:google-api-client:2.2.0")
  implementation("com.google.auth:google-auth-library-oauth2-http:1.20.0")
  implementation("com.google.apis:google-api-services-drive:v3-rev20230822-2.0.0")
  implementation("com.opencsv:opencsv:5.9")
  implementation("com.squarespace.cldr-engine:cldr-engine:1.7.2")
  implementation("commons-validator:commons-validator:1.7")
  implementation("dev.akkinoc.spring.boot:logback-access-spring-boot-starter:4.0.0")
  implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
  implementation("io.ktor:ktor-client-java:$ktorVersion")
  implementation("io.ktor:ktor-serialization-jackson:$ktorVersion")
  implementation("io.swagger.core.v3:swagger-annotations:2.2.19")
  implementation("jakarta.inject:jakarta.inject-api:2.0.1")
  implementation("jakarta.ws.rs:jakarta.ws.rs-api:3.1.0")
  implementation("net.coobird:thumbnailator:0.4.20")
  implementation("org.apache.tika:tika-core:2.9.1")
  implementation("org.flywaydb:flyway-core:$flywayVersion")
  implementation("org.flywaydb:flyway-database-postgresql:$flywayVersion")
  implementation("org.freemarker:freemarker:2.3.32")
  implementation("org.geotools:gt-epsg-hsql:$geoToolsVersion")
  implementation("org.geotools:gt-shapefile:$geoToolsVersion")
  implementation("org.jobrunr:jobrunr-spring-boot-3-starter:6.3.3")
  implementation("org.jooq:jooq:$jooqVersion")
  implementation("org.locationtech.jts:jts-core:$jtsVersion")
  implementation("org.locationtech.jts.io:jts-io-common:$jtsVersion")
  implementation(kotlin("reflect"))
  implementation("org.postgresql:postgresql:$postgresJdbcVersion")
  implementation(platform("software.amazon.awssdk:bom:$awsSdkVersion"))
  implementation("software.amazon.awssdk:sesv2")
  implementation("software.amazon.awssdk:rds")
  implementation("software.amazon.awssdk:s3")
  implementation("software.amazon.awssdk:sts")

  implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:$springDocVersion")

  runtimeOnly("net.logstash.logback:logstash-logback-encoder:7.3")

  testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
  testImplementation("io.mockk:mockk:1.13.8")
  testImplementation("org.geotools:gt-epsg-hsql:$geoToolsVersion")
  testImplementation("org.hsqldb:hsqldb:2.7.2")
  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("org.springframework.security:spring-security-test")
  testImplementation(platform("org.testcontainers:testcontainers-bom:1.19.3"))
  testImplementation("org.testcontainers:junit-jupiter")
  testImplementation("org.testcontainers:postgresql")

  developmentOnly("org.springframework.boot:spring-boot-devtools")
  developmentOnly("com.h2database:h2")
  dokkaPlugin("com.glureau:html-mermaid-dokka-plugin:0.4.6")
}

tasks.register("downloadDependencies") {
  fun ConfigurationContainer.resolveAll() =
      this.filter {
            it.isCanBeResolved && it !is DeprecatableConfiguration && !it.name.contains("Metadata")
          }
          .forEach { it.resolve() }

  doLast {
    configurations.resolveAll()
    buildscript.configurations.resolveAll()
  }
}

testing {
  suites {
    val test by
        getting(JvmTestSuite::class) {
          useJUnitJupiter()

          targets {
            all {
              testTask.configure {
                systemProperty("java.locale.providers", "SPI,CLDR")
                testLogging { exceptionFormat = TestExceptionFormat.FULL }
              }
            }
          }
        }
  }
}

val generateVersionFile = tasks.register<VersionFileTask>("generateVersionFile")

val generatePostgresDockerConfig =
    tasks.register<PostgresDockerConfigTask>("generatePostgresDockerConfig")

val renderGibberishTask =
    tasks.register<com.terraformation.gradle.RenderGibberishTask>("renderGibberish")
val renderMjmlTask = tasks.register<com.terraformation.gradle.RenderMjmlTask>("renderMjml")

tasks {
  processResources {
    dependsOn(renderGibberishTask)
    dependsOn(renderMjmlTask)
    exclude("**/*.mjml")
  }

  generateJooqClasses {
    basePackageName = "com.terraformation.backend.db"
    schemas = listOf("public", "nursery", "seedbank", "tracking")
    outputSchemaToDefault.add("public")

    usingJavaConfig {
      val generator = com.terraformation.backend.jooq.TerrawareGenerator()
      val pluralStrategy = com.terraformation.backend.jooq.PluralPojoStrategy()

      name = generator.javaClass.name
      strategy = Strategy().withName(pluralStrategy.javaClass.name)
      database.apply {
        withName("org.jooq.meta.postgres.PostgresDatabase")
        withIncludes(".*")
        withExcludes(generator.excludes())
        withForcedTypes(generator.forcedTypes(basePackageName.get()))
        withEmbeddables(generator.embeddables())
        // Fix compiler warnings for PostGIS functions; see https://github.com/jOOQ/jOOQ/issues/8587
        withTableValuedFunctions(false)
      }

      generate.apply {
        isDaos = true
        isEmbeddables = true
        isJavaTimeTypes = true
        isPojos = true
        isPojosAsKotlinDataClasses = true
        isRecords = true
        isRoutines = false
        isSpringAnnotations = true
      }
    }

    flywayProperties.put("flyway.placeholders.jsonColumnType", "JSONB")
    flywayProperties.put("flyway.placeholders.uuidColumnType", "UUID")
  }
}

jooq {
  withContainer {
    image {
      val postgresDockerRepository: String by project
      val postgresDockerTag: String by project

      name = "$postgresDockerRepository:$postgresDockerTag"
    }
  }
}

sourceSets.main { java.srcDir("build/generated/kotlin") }

sourceSets.test { java.srcDir("build/generated-test/kotlin") }

java {
  toolchain { languageVersion = JavaLanguageVersion.of(21) }
  targetCompatibility = JavaVersion.VERSION_21
}

node { yarnVersion = "1.22.17" }

tasks.withType<KotlinCompile> {
  compilerOptions {
    // Kotlin and Java target compatibility must be the same.
    jvmTarget = JvmTarget.JVM_21
    allWarningsAsErrors = true
  }

  dependsOn(generateVersionFile)

  if (name == "compileTestKotlin") {
    dependsOn(generatePostgresDockerConfig)
  }
}

tasks.withType<KaptGenerateStubsTask> { dependsOn(tasks.generateJooqClasses) }

tasks.getByName<BootJar>("bootJar") {
  // Don't package local development settings in the distribution.
  exclude("application-dev*.yaml")
}

spotless {
  val ktfmtVersion: String by project
  kotlin {
    ktfmt(ktfmtVersion)
    targetExclude("build/**")
  }
  kotlinGradle {
    ktfmt(ktfmtVersion)
    target("*.gradle.kts", "buildSrc/*.gradle.kts")
  }
}

openApi {
  // Run the server on a port that's unlikely to already be in use.
  val listenPort = 32109

  customBootRun {
    // Use application-apidoc.yaml for application configuration.
    jvmArgs.add("-Dspring.profiles.active=apidoc")

    jvmArgs.add("-Djava.locale.providers=SPI,CLDR,COMPAT")
    jvmArgs.add("-Dserver.port=$listenPort")

    // Spring Boot Devtools aren't useful for a one-shot server run, and they add log output.
    classpath.setFrom(
        sourceSets.main.get().runtimeClasspath.filter { "spring-boot-devtools" !in it.name })
  }

  apiDocsUrl = "http://localhost:$listenPort/v3/api-docs.yaml"

  outputDir = projectDir
  outputFileName = "openapi.yaml"
}

tasks.register<JavaExec>("generateFrontEndTestSession") {
  group = "Execution"
  description = "Generates a fake login session for the frontend integration test suite."
  classpath = sourceSets.test.get().runtimeClasspath
  mainClass = "com.terraformation.backend.customer.FrontEndTestSessionGeneratorKt"
}

licenseReport {
  configurations =
      project.configurations.filter { it.isCanBeResolved }.map { it.name }.toTypedArray()
  excludeBoms = true
  excludes =
      arrayOf(
          // Spring Boot has licenses in the individual dependencies, not the umbrella artifact.
          "org.springframework.boot:spring-boot-dependencies",
          // https://github.com/jk1/Gradle-License-Report/pull/243
          "software.amazon.awssdk:bom",
      )
  filters =
      arrayOf(LicenseBundleNormalizer("$projectDir/src/docs/license-normalizer-bundle.json", true))
  outputDir = "$projectDir/docs/license-report"
  renderers = arrayOf(InventoryHtmlReportRenderer())
}

tasks.withType<DokkaTask>().configureEach {
  dokkaSourceSets {
    named("main") {
      outputDirectory = file("docs/dokka")
      moduleName = "Terraware Server"
      includes.from(fileTree("src/main/kotlin") { include("**/Package.md") })
      sourceLink {
        localDirectory = file("src/main/kotlin")
        remoteUrl =
            URI("https://github.com/terraware/terraware-server/tree/main/src/main/kotlin").toURL()
        remoteLineSuffix = "#L"
      }
    }
  }
}
