plugins {
  `kotlin-dsl`
}

repositories {
  mavenCentral()
}

dependencies {
  // Make sure this matches the jOOQ version used in the main project!
  implementation("org.jooq:jooq-codegen:3.14.7")
  implementation("org.eclipse.jgit:org.eclipse.jgit:5.10.+")
}
