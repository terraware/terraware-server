import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins { `kotlin-dsl` }

// Needed to avoid noisy warning message from Gradle. Won't be needed in Gradle 7.5.
// https://github.com/gradle/gradle/issues/18935 https://github.com/gradle/gradle/issues/19308
java.targetCompatibility = JavaVersion.VERSION_1_8

tasks.withType<KotlinCompile> { kotlinOptions.jvmTarget = java.targetCompatibility.majorVersion }

repositories {
  gradlePluginPortal()
  mavenCentral()
}

dependencies {
  implementation("com.github.node-gradle:gradle-node-plugin:3.5.0")
  implementation("org.eclipse.jgit:org.eclipse.jgit:5.10.+")
}
