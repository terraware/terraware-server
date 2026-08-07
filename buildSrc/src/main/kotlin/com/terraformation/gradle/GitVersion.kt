package com.terraformation.gradle

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.gradle.api.Project

/**
 * Returns a version number based on the current git revision, if available. The version number is
 * of the form `[baseVersion-]gitRev[-SNAPSHOT]`. The `-SNAPSHOT` suffix is appended if the repo is
 * dirty, that is, if `git status` would show edits or untracked files.
 *
 * If the project isn't in a git repo, returns a version number of the form `baseVersion-SNAPSHOT`.
 */
fun Project.computeGitVersion(baseVersion: String?): String {
  val repoBuilder = FileRepositoryBuilder().findGitDir(projectDir)
  val versionParts =
      if (repoBuilder.gitDir != null) {
        val repo = repoBuilder.build()
        val head = repo.findRef("HEAD")
        val clean = Git(repo).status().call().isClean

        listOfNotNull(
            baseVersion,
            repo.newObjectReader().abbreviate(head.objectId).name(),
            if (clean) null else "SNAPSHOT",
        )
      } else {
        listOf(baseVersion ?: "0.0.1", "SNAPSHOT")
      }

  return versionParts.joinToString("-")
}
