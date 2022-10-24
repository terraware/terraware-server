import com.github.jk1.license.filter.LicenseBundleNormalizer
import com.github.jk1.license.render.InventoryHtmlReportRenderer
import com.terraformation.gradle.PostgresDockerConfigTask
import com.terraformation.gradle.VersionFileTask
import com.terraformation.gradle.computeGitVersion
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.internal.deprecation.DeprecatableConfiguration
import org.jetbrains.kotlin.gradle.internal.KaptGenerateStubsTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jooq.meta.jaxb.Strategy
import org.springframework.boot.gradle.tasks.bundling.BootJar
import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
  kotlin("jvm")
  kotlin("plugin.allopen")
  kotlin("plugin.spring")

  // kapt is required to build the metadata for application.yaml autocomplete of our
  // config settings, but slows the build down significantly; disable it by default.
  // Uncomment the kapt line in the dependencies block if you enable this.
  // kotlin("kapt")

  id("com.revolut.jooq-docker") version "0.3.7"
  id("com.diffplug.spotless") version "6.4.2"
  id("org.springframework.boot") version "2.7.5"
  id("io.spring.dependency-management") version "1.1.0"

  // Add the build target to generate Swagger docs
  id("com.github.johnrengelman.processes") version "0.5.0"
  id("org.springdoc.openapi-gradle-plugin") version "1.3.4"

  id("com.github.jk1.dependency-license-report") version "2.1"

  id("terraware-jooq")
}

buildscript {
  // Force the jOOQ codegen plugin to use the same jOOQ version we use in the application code.
  val jooqVersion: String by project
  configurations.classpath {
    resolutionStrategy {
      setForcedModules(
          // https://github.com/revolut-engineering/jooq-plugin/pull/17
          "com.github.docker-java:docker-java-transport-okhttp:3.2.12",
          "org.jooq:jooq:$jooqVersion",
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

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

node { yarnVersion.set("1.22.17") }

repositories { mavenCentral() }

dependencies {
  val awsSdkVersion: String by project
  val jacksonVersion: String by project
  val jooqVersion: String by project
  val jUnitVersion: String by project
  val keycloakVersion: String by project
  val postgresJdbcVersion: String by project
  val springDocVersion: String by project

  jdbc("org.postgresql:postgresql:$postgresJdbcVersion")

  // Build autocomplete metadata for our config settings in application.yaml. This
  // requires kapt which slows the build down significantly, so is commented out.
  // Uncomment the kotlin("kapt") line above if you enable this.
  // kapt("org.springframework.boot:spring-boot-configuration-processor")

  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.boot:spring-boot-starter-jooq")
  implementation("org.springframework.boot:spring-boot-starter-mail")
  implementation("org.springframework.boot:spring-boot-starter-security")
  implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
  implementation("org.springframework.boot:spring-boot-starter-validation")
  implementation("org.springframework.session:spring-session-jdbc")

  implementation("com.fasterxml.jackson:jackson-bom:$jacksonVersion")
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
  implementation("com.opencsv:opencsv:5.7.0")
  implementation("commons-fileupload:commons-fileupload:1.4")
  implementation("commons-validator:commons-validator:1.7")
  implementation("dev.akkinoc.spring.boot:logback-access-spring-boot-starter:3.4.0")
  implementation("io.swagger.core.v3:swagger-annotations:2.2.4")
  implementation("javax.inject:javax.inject:1")
  implementation("net.coobird:thumbnailator:0.4.17")
  implementation("net.postgis:postgis-jdbc:2021.1.0")
  implementation("org.apache.tika:tika-core:2.5.0")
  implementation("org.flywaydb:flyway-core:9.5.1")
  implementation("org.freemarker:freemarker:2.3.31")
  implementation("org.jobrunr:jobrunr-spring-boot-starter:5.3.0")
  implementation("org.jooq:jooq:$jooqVersion")
  implementation(platform("org.keycloak.bom:keycloak-adapter-bom:$keycloakVersion"))
  implementation("org.keycloak:keycloak-spring-boot-starter")
  implementation("org.keycloak:keycloak-admin-client:$keycloakVersion")
  implementation(kotlin("reflect"))
  implementation("org.postgresql:postgresql:$postgresJdbcVersion")
  implementation(platform("software.amazon.awssdk:bom:$awsSdkVersion"))
  implementation("software.amazon.awssdk:sesv2")
  implementation("software.amazon.awssdk:rds")
  implementation("software.amazon.awssdk:s3")
  implementation("software.amazon.awssdk:sts")

  implementation("org.springdoc:springdoc-openapi-kotlin:$springDocVersion")
  implementation("org.springdoc:springdoc-openapi-security:$springDocVersion")
  implementation("org.springdoc:springdoc-openapi-ui:$springDocVersion")

  runtimeOnly("net.logstash.logback:logstash-logback-encoder:7.2")

  testImplementation("io.mockk:mockk:1.13.2")
  testImplementation("org.junit.jupiter:junit-jupiter-api:$jUnitVersion")
  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("org.springframework.security:spring-security-test")
  testImplementation(platform("org.testcontainers:testcontainers-bom:1.17.5"))
  testImplementation("org.testcontainers:junit-jupiter")
  testImplementation("org.testcontainers:postgresql")

  testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$jUnitVersion")

  developmentOnly("org.springframework.boot:spring-boot-devtools")
}

tasks.register("downloadDependencies") {
  fun ConfigurationContainer.resolveAll() =
      this.filter {
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

val generateVersionFile = tasks.register<VersionFileTask>("generateVersionFile")

val generatePostgresDockerConfig =
    tasks.register<PostgresDockerConfigTask>("generatePostgresDockerConfig")

val renderMjmlTask = tasks.register<com.terraformation.gradle.RenderMjmlTask>("renderMjml")

tasks {
  processResources {
    dependsOn(renderMjmlTask)
    exclude("**/*.mjml")
  }

  generateJooqClasses {
    basePackageName = "com.terraformation.backend.db"
    excludeFlywayTable = true
    schemas = arrayOf("public", "nursery", "seedbank")
    outputSchemaToDefault = setOf("public")

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

    flywayProperties =
        mapOf(
            "flyway.placeholders.jsonColumnType" to "JSONB",
            "flyway.placeholders.uuidColumnType" to "UUID",
        )
  }
}

jooq {
  image {
    val postgresDockerRepository: String by project
    val postgresDockerTag: String by project

    repository = postgresDockerRepository
    tag = postgresDockerTag
  }
}

sourceSets.main { java.srcDir("build/generated/kotlin") }

sourceSets.test { java.srcDir("build/generated-test/kotlin") }

tasks.withType<KotlinCompile> {
  dependsOn(generateVersionFile)
  kotlinOptions.allWarningsAsErrors = true
  kotlinOptions.jvmTarget = "17"

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
  val bootRun = project.tasks["bootRun"] as BootRun

  // Run the server on a port that's unlikely to already be in use.
  val listenPort = 32109
  bootRun.jvmArgs("-Dserver.port=$listenPort")
  apiDocsUrl.set("http://localhost:$listenPort/v3/api-docs.yaml")

  // Use application-apidoc.yaml for application configuration.
  bootRun.jvmArgs("-Dspring.profiles.active=apidoc")

  outputDir.set(projectDir)
  outputFileName.set("openapi.yaml")
}

tasks.register<JavaExec>("generateFrontEndTestSession") {
  group = "Execution"
  description = "Generates a fake login session for the frontend integration test suite."
  classpath = sourceSets.test.get().runtimeClasspath
  mainClass.set("com.terraformation.backend.customer.FrontEndTestSessionGeneratorKt")
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
