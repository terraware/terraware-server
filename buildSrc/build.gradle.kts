plugins {
  `kotlin-dsl`
}

repositories {
  jcenter()
  mavenCentral()
}

dependencies {
  // Make sure this matches the jOOQ version used in the main project!
  implementation("org.jooq:jooq-codegen:3.14.4")
}
