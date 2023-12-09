import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins { `kotlin-dsl` }

repositories {
  gradlePluginPortal()
  mavenCentral()
}

dependencies {
  implementation("com.github.node-gradle:gradle-node-plugin:7.0.1")
  implementation("org.eclipse.jgit:org.eclipse.jgit:6.8.+")
}

java { targetCompatibility = JavaVersion.VERSION_19 }

tasks.withType<KotlinCompile> {
  compilerOptions {
    jvmTarget = JvmTarget.JVM_19
    allWarningsAsErrors = true
  }
}
