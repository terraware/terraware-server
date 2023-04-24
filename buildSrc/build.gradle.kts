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

tasks.withType<KotlinCompile> {
  compilerOptions {
    jvmTarget.set(
        rootProject.tasks.withType<KotlinCompile>().first().compilerOptions.jvmTarget.get())
    allWarningsAsErrors.set(true)
  }
}
