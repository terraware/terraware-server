import com.github.jk1.license.filter.LicenseBundleNormalizer
import com.github.jk1.license.render.InventoryHtmlReportRenderer
import com.terraformation.gradle.PostgresDockerConfigTask
import com.terraformation.gradle.VersionFileTask
import com.terraformation.gradle.computeGitVersion
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.internal.deprecation.DeprecatableConfiguration
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

  id("dev.monosoul.jooq-docker") version "7.0.22"
  id("com.diffplug.spotless") version "7.2.1"
  id("org.springframework.boot") version "3.5.5"
  id("io.spring.dependency-management") version "1.1.7"

  // Add the build target to generate Swagger docs
  id("com.github.johnrengelman.processes") version "0.5.0"
  id("org.springdoc.openapi-gradle-plugin") version "1.9.0"

  id("com.github.jk1.dependency-license-report") version "2.9"

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
  val jjwtVersion: String by project
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
  implementation("org.springframework.boot:spring-boot-starter-actuator")
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

  implementation(platform("org.springframework.ai:spring-ai-bom:1.0.2"))
  implementation("org.springframework.ai:spring-ai-starter-model-openai")
  implementation("org.springframework.ai:spring-ai-starter-vector-store-pgvector")
  implementation("org.springframework.ai:spring-ai-advisors-vector-store")
  implementation("org.springframework.ai:spring-ai-rag")
  implementation("org.springframework.ai:spring-ai-tika-document-reader")

  implementation("ch.qos.logback.access:logback-access-tomcat:2.0.6")
  implementation("com.drewnoakes:metadata-extractor:2.19.0")
  implementation("com.dropbox.core:dropbox-core-sdk:7.0.0")
  implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml")
  implementation("com.google.api-client:google-api-client:2.8.1")
  implementation("com.google.auth:google-auth-library-oauth2-http:1.39.0")
  implementation("com.google.apis:google-api-services-drive:v3-rev20250910-2.0.0")
  implementation("com.opencsv:opencsv:5.12.0")
  implementation("com.pgvector:pgvector:0.1.6")
  implementation("com.squarespace.cldr-engine:cldr-engine:1.12.0")
  implementation("commons-codec:commons-codec:1.19.0")
  implementation("commons-validator:commons-validator:1.10.0")
  implementation("dev.akkinoc.spring.boot:logback-access-spring-boot-starter:4.7.0")
  implementation("io.jsonwebtoken:jjwt-api:$jjwtVersion")
  implementation("io.ktor:ktor-client-auth:$ktorVersion")
  implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
  implementation("io.ktor:ktor-client-java:$ktorVersion")
  implementation("io.ktor:ktor-serialization-jackson:$ktorVersion")
  implementation("io.swagger.core.v3:swagger-annotations:2.2.36")
  implementation("jakarta.inject:jakarta.inject-api:2.0.1")
  implementation("jakarta.ws.rs:jakarta.ws.rs-api:4.0.0")
  implementation("net.coobird:thumbnailator:0.4.20")
  implementation("org.apache.tika:tika-core:3.2.2")
  implementation("org.bouncycastle:bcpkix-jdk18on:1.81")
  implementation("org.commonmark:commonmark:0.26.0")
  implementation("org.flywaydb:flyway-core:$flywayVersion")
  implementation("org.flywaydb:flyway-database-postgresql:$flywayVersion")
  implementation("org.freemarker:freemarker:2.3.34")
  implementation("org.geotools:gt-epsg-hsql:$geoToolsVersion")
  implementation("org.geotools:gt-geojson:$geoToolsVersion")
  implementation("org.geotools:gt-shapefile:$geoToolsVersion")
  implementation("org.geotools:gt-xml:$geoToolsVersion")
  implementation("org.geotools.xsd:gt-xsd-core:$geoToolsVersion")
  implementation("org.geotools.xsd:gt-xsd-kml:$geoToolsVersion")
  implementation("org.jobrunr:jobrunr-spring-boot-3-starter:8.0.2")
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
  implementation("software.amazon.jdbc:aws-advanced-jdbc-wrapper:2.6.4")

  implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:$springDocVersion")

  runtimeOnly("net.logstash.logback:logstash-logback-encoder:8.1")
  runtimeOnly("io.jsonwebtoken:jjwt-impl:$jjwtVersion")
  runtimeOnly("io.jsonwebtoken:jjwt-jackson:$jjwtVersion")

  testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
  testImplementation("io.mockk:mockk:1.14.5")
  testImplementation("org.geotools:gt-epsg-hsql:$geoToolsVersion")
  testImplementation("org.hsqldb:hsqldb:2.7.4")
  testImplementation("org.junit.platform:junit-platform-launcher")
  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("org.springframework.security:spring-security-test")
  testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.3"))
  testImplementation("org.testcontainers:junit-jupiter")
  testImplementation("org.testcontainers:postgresql")

  developmentOnly("org.springframework.boot:spring-boot-devtools")
  developmentOnly("com.h2database:h2")
}

configurations.configureEach {
  // Spring includes its own Commons Logging implementation which can conflict with the official one
  exclude("commons-logging")
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
    schemas =
        listOf("public", "docprod", "accelerator", "nursery", "seedbank", "tracking", "funder")
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
        isKotlinSetterJvmNameAnnotationsOnIsPrefix = false
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
  toolchain { languageVersion = JavaLanguageVersion.of(24) }
  targetCompatibility = JavaVersion.VERSION_23
}

node { yarnVersion = "1.22.17" }

tasks.withType<KotlinCompile> {
  compilerOptions {
    // Kotlin and Java target compatibility must be the same.
    jvmTarget = JvmTarget.JVM_23
    allWarningsAsErrors = true

    extraWarnings = true

    // jOOQ generated code has redundant modifiers
    freeCompilerArgs.add("-Xsuppress-warning=REDUNDANT_MODALITY_MODIFIER")
    freeCompilerArgs.add("-Xsuppress-warning=REDUNDANT_VISIBILITY_MODIFIER")
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
    target("src/**/*.kt", "buildSrc/**/*.kt", "jooq/**/*.kt")
  }
  kotlinGradle {
    ktfmt(ktfmtVersion)
    target("*.gradle.kts", "buildSrc/*.gradle.kts", "jooq/*.gradle.kts")
  }
  flexmark {
    target("*.md", "buildSrc/*.md", "docs/*.md", "src/**/*.md")
    flexmark()
  }
  format("sql") {
    // Only apply to newly-added SQL files since editing existing ones will cause the checksums
    // to change which Flyway will detect as invalid edits of already-applied migrations.
    ratchetFrom("origin/main")

    target("src/**/*.sql")
    endWithNewline()
    trimTrailingWhitespace()
  }
}

openApi {
  // Run the server on a port that's unlikely to already be in use.
  val listenPort = 32109

  customBootRun {
    // Use application-apidoc.yaml for application configuration.
    jvmArgs.add("-Dspring.profiles.active=apidoc")

    jvmArgs.add("-Djava.locale.providers=SPI,CLDR")
    jvmArgs.add("-Dserver.port=$listenPort")

    // Spring Boot Devtools aren't useful for a one-shot server run, and they add log output.
    classpath.setFrom(
        sourceSets.main.get().runtimeClasspath.filter { "spring-boot-devtools" !in it.name }
    )
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
      )
  filters =
      arrayOf(LicenseBundleNormalizer("$projectDir/src/docs/license-normalizer-bundle.json", true))
  outputDir = "$projectDir/docs/license-report"
  renderers = arrayOf(InventoryHtmlReportRenderer())
}
