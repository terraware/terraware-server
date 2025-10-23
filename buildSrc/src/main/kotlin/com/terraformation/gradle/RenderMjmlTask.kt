package com.terraformation.gradle

import com.github.gradle.node.NodeExtension
import com.github.gradle.node.exec.NodeExecConfiguration
import com.github.gradle.node.util.DefaultProjectApiHelper
import com.github.gradle.node.variant.VariantComputer
import com.github.gradle.node.yarn.exec.YarnExecRunner
import com.github.gradle.node.yarn.task.YarnInstallTask
import java.io.File
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
  private val templatesSourceDir = resourcesSourceDir.resolve("templates/email")
  private val resourcesBuildDir = layout.buildDirectory.dir("resources/main").get().asFile

  @get:IgnoreEmptyDirectories
  @get:InputFiles
  @get:SkipWhenEmpty
  val mjmlFiles: ConfigurableFileCollection =
      objectFactory
          .fileCollection()
          .from(objectFactory.fileTree().from(templatesSourceDir).include("**/body.ftlh.mjml"))

  @get:OutputDirectory val outputDir = resourcesBuildDir.resolve("templates/email")

  init {
    group = "build"
    description = "Renders MJML templates."
    @Suppress("LeakingThis") // dependsOn is non-final, but that's harmless here.
    dependsOn(YarnInstallTask.NAME)
  }

  @TaskAction
  fun exec(changes: InputChanges) {
    val fileChanges = changes.getFileChanges(mjmlFiles).toList()

    fileChanges
        .filter { it.fileType != FileType.DIRECTORY && it.changeType == ChangeType.REMOVED }
        .forEach { getTargetFile(it.file).delete() }

    val filesToRender =
        if (!changes.isIncremental) {
          mjmlFiles.filter { it.isFile }.map { it to getTargetFile(it) }
        } else {
          fileChanges
              .filter { it.fileType != FileType.DIRECTORY && it.changeType != ChangeType.REMOVED }
              .map { it.file to getTargetFile(it.file) }
        }

    if (filesToRender.isNotEmpty()) {
      renderMjmlFiles(filesToRender)
    }
  }

  /** Runs Yarn to render one or more MJML files in a single Node process. */
  private fun renderMjmlFiles(files: List<Pair<File, File>>) {
    val command = mutableListOf("mjml", templatesSourceDir.absolutePath, outputDir.absolutePath)
    files.forEach { (source) -> command.add(source.relativeTo(templatesSourceDir).path) }

    val runner = objectFactory.newInstance(YarnExecRunner::class.java)
    runner.executeYarnCommand(
        objectFactory.newInstance(DefaultProjectApiHelper::class.java),
        nodeExtension,
        NodeExecConfiguration(command),
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
  private fun getTargetFile(sourceFile: File): File {
    val mjmlExtension = ".mjml"

    if (!sourceFile.name.endsWith(mjmlExtension)) {
      throw IllegalArgumentException("File $sourceFile is not an MJML file")
    }

    val targetRelativeToResourcesDir =
        File(sourceFile.path.substring(0, sourceFile.path.length - mjmlExtension.length))
            .relativeTo(resourcesSourceDir)

    return resourcesBuildDir.resolve(targetRelativeToResourcesDir)
  }
}
