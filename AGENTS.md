# Coding Agent Instructions

## Build/Test Commands

- Build: `./gradlew testClasses`
- Run: `./gradlew bootRun`
- Format code: `./gradlew spotlessApply`
- Run all tests: `./gradlew test`
- Run single test: `./gradlew test --tests "com.terraformation.backend.package.ClassName"`
- Generate API docs: `./gradlew generateOpenApiDocs`

Run ./gradlew commands one at a time, serially, rather than launching multiple of them in the background. Concurrent Gradle runs can cause inconsistent results and can trigger unnecessary recompilation.

## Code Style Guidelines

- See the file docs/CONVENTIONS.md for coding conventions.
- Always include trailing newlines in source files, including HTML and MJML files.
- Avoid adding comments that say obvious things about what the code does.
- The intended audience for comments is someone looking at the code in the future, long after your change has been merged. Don't write comments about how the code used to work or about why it was changed; future readers will care about the current behavior of the code, not its evolution over time.
- Prefer imperative present tense in commit titles and in the parts of commit bodies that say what is changing.

## Workflow

Check whether the current repo is using Jujutsu (jj) rather than plain git; if so, prefer jj commands for examining history and checkpointing your work.

If you are making the same change across lots of files, prefer writing a temporary script rather than editing each file one by one yourself. Always test the script against a few files first to make sure it's working as intended. If you can't get the script working right after a couple attempts, give up and manually edit the files.

Format the code when you're done working. There's no need to rerun tests after code formatting.

## Pull Requests

PR descriptions should focus on why the change was needed and what changed at a high level. Don't talk about choices that were considered and ruled out, and don't go into detail about the actual implementation; people can read the code diff to see low-level details.

We use stacked pull requests for changes of significant size. Stacks should be organized such that merging the first part of the stack still leaves the code base in a working, deployable state. That is, no PR in a stack can leave the system nonfunctional.

A stack of PRs should represent a clear, logical sequence of internally-consistent incremental steps that add up to the desired goal. It is expected for later PRs in a stack to depend on earlier ones. It's also fine for a PR to include small amounts of temporary code that gets rewritten or removed by later PRs, if that helps the earlier PRs stay coherent.

When possible, keep each PR in a stack under 350 lines of changes, but only if you can meet the "each PR is a complete change that leaves the code base in a working state" goal. 350 lines is a maximum, not a minimum; it's fine to split a change into smaller chunks than that.

If the repo is using jj, you can use jj commands to manage the stack of revisions, including splitting, combining, and reordering revisions.

Don't push or submit the pull requests yourself; that'll be done manually.

## Tool Use

If the Jetbrains MCP service is available, don't use it to run Gradle or other shell commands; run those using Bash instead. But use the Jetbrains MCP service for everything other than running commands.

If the Context7 MCP service is available, use it to look up documentation for any libraries you aren't sure how to use correctly.

## Skill Use

If you are able to use Claude-style skills, do so when appropriate instead of figuring tasks out from scratch.
