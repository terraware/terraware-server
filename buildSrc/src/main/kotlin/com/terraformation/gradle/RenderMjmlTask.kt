package com.terraformation.gradle

import com.github.gradle.node.NodeExtension
import com.github.gradle.node.exec.NodeExecConfiguration
import com.github.gradle.node.util.PlatformHelper
import com.github.gradle.node.util.ProjectApiHelper
import com.github.gradle.node.variant.VariantComputer
import com.github.gradle.node.yarn.exec.YarnExecRunner
import com.github.gradle.node.yarn.task.YarnSetupTask
import java.io.File
import java.nio.file.Files
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.FileType
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.get
import org.gradle.work.ChangeType
import org.gradle.work.FileChange
import org.gradle.work.InputChanges

/** Renders MJML email bodies to FreeMarker templates that can be evaluated at runtime. */
abstract class RenderMjmlTask : DefaultTask() {
  @get:IgnoreEmptyDirectories
  @get:InputFiles
  @get:SkipWhenEmpty
  val mjmlFiles =
      project.files(
          project.fileTree("src/main/resources/templates/email") { include("**/body.ftlh.mjml") })

  @get:OutputDirectory val outputDir = project.buildDir.resolve("resources/main/templates/email")

  @get:Internal val projectHelper = ProjectApiHelper.newInstance(project)

  @get:Inject abstract val objects: ObjectFactory

  init {
    group = "build"
    description = "Renders MJML templates."
    dependsOn(YarnSetupTask.NAME)
  }

  @TaskAction
  fun exec(changes: InputChanges) {
    changes.getFileChanges(mjmlFiles).forEach { change ->
      if (change.fileType != FileType.DIRECTORY) {
        val targetFile = getTargetFile(change)

        if (change.changeType == ChangeType.REMOVED) {
          targetFile.delete()
        } else {
          renderMjmlFile(change.file, targetFile)
        }
      }
    }
  }

  /** Runs Yarn to format a single MJML file. */
  private fun renderMjmlFile(mjmlFile: File, targetFile: File) {
    Files.createDirectories(targetFile.toPath().parent)
    val runner = objects.newInstance(YarnExecRunner::class.java)

    runner.executeYarnCommand(
        projectHelper,
        NodeExtension[project],
        NodeExecConfiguration(
            listOf(
                "mjml",
                "--config.minify",
                "true",
                "--config.beautify",
                "false",
                "-o",
                "$targetFile",
                "$mjmlFile")),
        VariantComputer(PlatformHelper.INSTANCE))
  }

  /**
   * Returns the target path for the given MJML file. The upper levels of directory structure are a
   * little different in the src and build directories; we want the following mapping:
   *
   * `src/main/resources/templates/email/a/b.ftlh.mjml` ->
   * `build/resources/main/templates/email/a/b.ftlh`
   */
  private fun getTargetFile(change: FileChange): File {
    val mjmlExtension = ".mjml"

    if (!change.file.name.endsWith(mjmlExtension)) {
      throw IllegalArgumentException("File ${change.file} is not an MJML file")
    }

    val targetRelativeToResourcesDir =
        File(change.file.path.substring(0, change.file.path.length - mjmlExtension.length))
            .relativeTo(project.projectDir.resolve("src/main/resources"))

    return project.buildDir.resolve("resources/main").resolve(targetRelativeToResourcesDir)
  }
}
