import com.github.jk1.license.filter.LicenseBundleNormalizer
import com.github.jk1.license.render.InventoryHtmlReportRenderer
import com.terraformation.gradle.PostgresDockerConfigTask
import com.terraformation.gradle.VersionFileTask
import com.terraformation.gradle.computeGitVersion
import java.net.URL
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.internal.deprecation.DeprecatableConfiguration
import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.internal.KaptGenerateStubsTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jooq.meta.jaxb.Strategy
import org.springdoc.openapi.gradle.plugin.FORKED_SPRING_BOOT_RUN_TASK_NAME
import org.springdoc.openapi.gradle.plugin.OpenApiGeneratorTask
import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
  kotlin("jvm")
  kotlin("plugin.allopen")
  kotlin("plugin.spring")

  // kapt is required to build the metadata for application.yaml autocomplete of our
  // config settings, but slows the build down significantly; disable it by default.
  // Uncomment the kapt line in the dependencies block if you enable this.
  // kotlin("kapt")

  id("dev.monosoul.jooq-docker") version "3.0.16"
  id("com.diffplug.spotless") version "6.4.2"
  id("org.jetbrains.dokka") version "1.8.10"
  id("org.springframework.boot") version "2.7.10"
  id("io.spring.dependency-management") version "1.1.0"

  // Add the build target to generate Swagger docs
  id("com.github.johnrengelman.processes") version "0.5.0"
  id("org.springdoc.openapi-gradle-plugin") version "1.6.0"

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

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

node { yarnVersion.set("1.22.17") }

repositories {
  maven("https://repo.osgeo.org/repository/geotools-releases/")
  mavenCentral()
}

dependencies {
  val awsSdkVersion: String by project
  val geoToolsVersion: String by project
  val jacksonVersion: String by project
  val jooqVersion: String by project
  val jtsVersion: String by project
  val jUnitVersion: String by project
  val keycloakVersion: String by project
  val ktorVersion: String by project
  val postgresJdbcVersion: String by project
  val springDocVersion: String by project

  jooqCodegen("org.postgresql:postgresql:$postgresJdbcVersion")

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
  implementation("com.google.api-client:google-api-client:2.2.0")
  implementation("com.google.auth:google-auth-library-oauth2-http:1.16.0")
  implementation("com.google.apis:google-api-services-drive:v3-rev20230306-2.0.0")
  implementation("com.opencsv:opencsv:5.7.1")
  implementation("com.squarespace.cldr-engine:cldr-engine:1.6.5")
  implementation("commons-fileupload:commons-fileupload:1.5")
  implementation("commons-validator:commons-validator:1.7")
  implementation("dev.akkinoc.spring.boot:logback-access-spring-boot-starter:3.4.5")
  implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
  implementation("io.ktor:ktor-client-java:$ktorVersion")
  implementation("io.ktor:ktor-serialization-jackson:$ktorVersion")
  implementation("io.swagger.core.v3:swagger-annotations:2.2.9")
  implementation("javax.inject:javax.inject:1")
  implementation("net.coobird:thumbnailator:0.4.19")
  implementation("org.apache.tika:tika-core:2.7.0")
  implementation("org.flywaydb:flyway-core:9.16.3")
  implementation("org.freemarker:freemarker:2.3.32")
  implementation("org.geotools:gt-shapefile:$geoToolsVersion")
  implementation("org.jobrunr:jobrunr-spring-boot-starter:5.3.3")
  implementation("org.jooq:jooq:$jooqVersion")
  implementation(platform("org.keycloak.bom:keycloak-adapter-bom:$keycloakVersion"))
  implementation("org.keycloak:keycloak-spring-boot-starter")
  implementation("org.keycloak:keycloak-admin-client:$keycloakVersion")
  implementation("org.locationtech.jts:jts-core:$jtsVersion")
  implementation("org.locationtech.jts.io:jts-io-common:$jtsVersion")
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

  runtimeOnly("net.logstash.logback:logstash-logback-encoder:7.3")

  testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
  testImplementation("io.mockk:mockk:1.13.4")
  testImplementation("org.geotools:gt-epsg-hsql:$geoToolsVersion")
  testImplementation("org.hsqldb:hsqldb:2.7.1")
  testImplementation("org.junit.jupiter:junit-jupiter-api:$jUnitVersion")
  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("org.springframework.security:spring-security-test")
  testImplementation(platform("org.testcontainers:testcontainers-bom:1.18.0"))
  testImplementation("org.testcontainers:junit-jupiter")
  testImplementation("org.testcontainers:postgresql")

  testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$jUnitVersion")

  developmentOnly("org.springframework.boot:spring-boot-devtools")
  dokkaPlugin("com.glureau:html-mermaid-dokka-plugin:0.4.4")
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
  systemProperty("java.locale.providers", "SPI,CLDR,COMPAT")
  testLogging { exceptionFormat = TestExceptionFormat.FULL }
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
    basePackageName.set("com.terraformation.backend.db")
    schemas.set(listOf("public", "nursery", "seedbank", "tracking"))
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

tasks.withType<KotlinCompile> {
  compilerOptions {
    allWarningsAsErrors.set(true)
    jvmTarget.set(JvmTarget.JVM_17)
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

  apiDocsUrl.set("http://localhost:$listenPort/v3/api-docs.yaml")

  outputDir.set(projectDir)
  outputFileName.set("openapi.yaml")
}

// Work around https://github.com/springdoc/springdoc-openapi-gradle-plugin/issues/100
tasks.withType<OpenApiGeneratorTask> {
  afterEvaluate {
    tasks.named(FORKED_SPRING_BOOT_RUN_TASK_NAME) {
      dependsOn(tasks.named("inspectClassesForKotlinIC"))
    }
  }
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

tasks.withType<DokkaTask>().configureEach {
  dokkaSourceSets {
    named("main") {
      outputDirectory.set(file("docs/dokka"))
      moduleName.set("Terraware Server")
      includes.from(fileTree("src/main/kotlin") { include("**/Package.md") })
      sourceLink {
        localDirectory.set(file("src/main/kotlin"))
        remoteUrl.set(
            URL("https://github.com/terraware/terraware-server/tree/main/src/main/kotlin"))
        remoteLineSuffix.set("#L")
      }
    }
  }
}
