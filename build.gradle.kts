import com.github.gradle.node.yarn.task.YarnTask
import com.terraformation.gradle.computeGitVersion
import java.nio.file.Files
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.internal.deprecation.DeprecatableConfiguration
import org.jetbrains.kotlin.gradle.internal.KaptGenerateStubsTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.utils.fileUtils.withReplacedExtensionOrNull
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
  id("org.springframework.boot") version "2.6.7"
  id("io.spring.dependency-management") version "1.0.11.RELEASE"

  // Add the build target to generate Swagger docs
  id("com.github.johnrengelman.processes") version "0.5.0"
  id("org.springdoc.openapi-gradle-plugin") version "1.3.4"

  // The MJML -> HTML translator for email messages is a Node.js utility.
  id("com.github.node-gradle.node") version "3.2.1"
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
  implementation("com.opencsv:opencsv:5.6")
  implementation("commons-fileupload:commons-fileupload:1.4")
  implementation("commons-validator:commons-validator:1.7")
  implementation("dev.akkinoc.spring.boot:logback-access-spring-boot-starter:3.2.6")
  implementation("io.swagger.core.v3:swagger-annotations:2.2.0")
  implementation("javax.inject:javax.inject:1")
  implementation("net.coobird:thumbnailator:0.4.17")
  implementation("net.postgis:postgis-jdbc:2021.1.0")
  implementation("org.apache.tika:tika-core:2.4.0")
  implementation("org.flywaydb:flyway-core:8.5.10")
  implementation("org.freemarker:freemarker:2.3.31")
  implementation("org.jobrunr:jobrunr-spring-boot-starter:5.0.1")
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

  runtimeOnly("net.logstash.logback:logstash-logback-encoder:7.1.1")

  testImplementation("io.mockk:mockk:1.12.3")
  testImplementation("org.junit.jupiter:junit-jupiter-api")
  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("org.springframework.security:spring-security-test")
  testImplementation(platform("org.testcontainers:testcontainers-bom:1.17.1"))
  testImplementation("org.testcontainers:junit-jupiter")
  testImplementation("org.testcontainers:postgresql")

  testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.2")

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

val generateVersionFile by
    tasks.registering {
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

val generatePostgresDockerConfig by
    tasks.registering {
      val postgresDockerRepository: String by project
      val postgresDockerTag: String by project

      val generatedPath =
          File("$buildDir/generated-test/kotlin/com/terraformation/backend/db/DockerImage.kt")
              .toPath()

      inputs.property("postgresDockerRepository", postgresDockerRepository)
      inputs.property("postgresDockerTag", postgresDockerTag)
      outputs.file(generatedPath)

      doLast {
        Files.createDirectories(generatedPath.parent)
        Files.writeString(
            generatedPath,
            """package com.terraformation.backend.db
              |const val POSTGRES_DOCKER_REPOSITORY = "$postgresDockerRepository"
              |const val POSTGRES_DOCKER_TAG = "$postgresDockerTag"
              |""".trimMargin())
      }
    }

// The MJML -> HTML translator can only operate on one file at a time if the source and target files
// are in different directories, so we need to run it once per modified MJML file. But YarnTask only
// lets us run a single command per Gradle task. Register a separate task for each MJML file.

val processMjmlTasks =
    project.files(
            fileTree("$projectDir/src/main/resources/templates/email") { include("*/*.mjml") })
        .mapIndexed { index, mjmlFile ->
          tasks.register<YarnTask>("compileMjml$index") {
            // The upper levels of directory structure are a little different in the src and build
            // directories; we want the following mapping:
            //
            // src/main/resources/templates/email/a/b.ftlh.mjml ->
            // build/resources/main/templates/email/a/b.ftlh
            val htmlFile =
                buildDir
                    .resolve("resources/main")
                    .resolve(
                        mjmlFile.withReplacedExtensionOrNull(".mjml", "")!!.relativeTo(
                            File("$projectDir/src/main/resources")))

            // Stop these tasks from appearing in "./gradlew tasks" output.
            group = ""

            dependsOn("yarn")

            inputs.file(mjmlFile)
            outputs.file(htmlFile)

            args.set(
                listOf(
                    "mjml",
                    "--config.minify",
                    "true",
                    "--config.beautify",
                    "false",
                    "-o",
                    "$htmlFile",
                    "$mjmlFile"))
          }
        }

tasks {
  processResources {
    processMjmlTasks.forEach { dependsOn(it.get()) }
    exclude("**/*.mjml")
  }

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
            "flyway.locations" to
                listOf(
                        "src/main/resources/db/migration/dev",
                        "src/main/resources/db/migration/postgres",
                        "src/main/resources/db/migration/common")
                    .joinToString(",") { "filesystem:$projectDir/$it" },
            "flyway.placeholders.jsonColumnType" to "JSONB",
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
  kotlin {
    ktfmt("0.31")
    targetExclude("build/**")
  }
  kotlinGradle {
    ktfmt("0.31")
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
