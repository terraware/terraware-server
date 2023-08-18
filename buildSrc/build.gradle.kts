import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins { `kotlin-dsl` }

repositories {
  gradlePluginPortal()
  mavenCentral()
}

dependencies {
  implementation("com.github.node-gradle:gradle-node-plugin:6.0.0")
  implementation("org.eclipse.jgit:org.eclipse.jgit:5.10.+")
}

java { targetCompatibility = JavaVersion.VERSION_19 }

tasks.withType<KotlinCompile> {
  compilerOptions {
    jvmTarget = JvmTarget.JVM_19
    allWarningsAsErrors = true
  }
}
