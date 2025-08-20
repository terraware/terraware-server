package com.terraformation.gradle

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.gradle.api.Project

/**
 * Returns a version number based on the current git revision. The version number is of the form
 * `[baseVersion-]gitRev[-SNAPSHOT]`. The `-SNAPSHOT` suffix is appended if the repo is dirty, that
 * is, if `git status` would show edits or untracked files.
 */
fun Project.computeGitVersion(baseVersion: String?): String {
  val repo = FileRepositoryBuilder().findGitDir(projectDir).build()
  val head = repo.findRef("HEAD")
  val clean = Git(repo).status().call().isClean

  val versionParts =
      listOfNotNull(
          baseVersion,
          repo.newObjectReader().abbreviate(head.objectId).name(),
          if (clean) null else "SNAPSHOT",
      )

  return versionParts.joinToString("-")
}
