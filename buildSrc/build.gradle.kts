import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins { `kotlin-dsl` }

repositories {
  gradlePluginPortal()
  mavenCentral()
}

dependencies {
  implementation("com.github.node-gradle:gradle-node-plugin:3.5.1")
  implementation("org.eclipse.jgit:org.eclipse.jgit:5.10.+")
}

java { targetCompatibility = JavaVersion.VERSION_19 }

tasks.withType<KotlinCompile> {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_19)
    allWarningsAsErrors.set(true)
  }
}
