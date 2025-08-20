package com.terraformation.gradle

import com.github.gradle.node.NodeExtension
import com.github.gradle.node.exec.NodeExecConfiguration
import com.github.gradle.node.util.DefaultProjectApiHelper
import com.github.gradle.node.variant.VariantComputer
import com.github.gradle.node.yarn.exec.YarnExecRunner
import com.github.gradle.node.yarn.task.YarnInstallTask
import java.io.File
import java.nio.file.Files
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileType
import org.gradle.api.file.ProjectLayout
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskAction
import org.gradle.work.ChangeType
import org.gradle.work.FileChange
import org.gradle.work.InputChanges

/** Renders MJML email bodies to FreeMarker templates that can be evaluated at runtime. */
abstract class RenderMjmlTask
@Inject
constructor(
    layout: ProjectLayout,
    private val objectFactory: ObjectFactory,
    sourceSets: SourceSetContainer,
) : DefaultTask() {
  private val nodeExtension = NodeExtension[project]
  private val resourcesSourceDir =
      sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME).resources.srcDirs.first()
  private val resourcesBuildDir = layout.buildDirectory.dir("resources/main").get().asFile

  @get:IgnoreEmptyDirectories
  @get:InputFiles
  @get:SkipWhenEmpty
  val mjmlFiles: ConfigurableFileCollection =
      objectFactory
          .fileCollection()
          .from(
              objectFactory
                  .fileTree()
                  .from(resourcesSourceDir.resolve("templates/email"))
                  .include("**/body.ftlh.mjml")
          )

  @get:OutputDirectory val outputDir = resourcesBuildDir.resolve("templates/email")

  init {
    group = "build"
    description = "Renders MJML templates."
    @Suppress("LeakingThis") // dependsOn is non-final, but that's harmless here.
    dependsOn(YarnInstallTask.NAME)
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
    val runner = objectFactory.newInstance(YarnExecRunner::class.java)

    runner.executeYarnCommand(
        objectFactory.newInstance(DefaultProjectApiHelper::class.java),
        nodeExtension,
        NodeExecConfiguration(
            listOf(
                "mjml",
                "--config.useMjmlConfigOptions",
                "true",
                "-o",
                "$targetFile",
                "$mjmlFile",
            )
        ),
        VariantComputer(),
    )
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
            .relativeTo(resourcesSourceDir)

    return resourcesBuildDir.resolve(targetRelativeToResourcesDir)
  }
}
