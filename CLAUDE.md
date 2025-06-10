# Claude Instructions

## Build/Test Commands

- Build: `./gradlew testClasses`
- Run: `./gradlew bootRun`
- Format code: `./gradlew spotlessApply`
- Run all tests: `./gradlew test`
- Run single test: `./gradlew test --tests "com.terraformation.backend.package.ClassName"`
- Generate API docs: `./gradlew generateOpenApiDocs`

## Code Style Guidelines

- See the file docs/CONVENTIONS.md for coding conventions.
- Always include trailing newlines in source files, including HTML and MJML files.
- Avoid adding comments that say obvious things about what the code does.

## Workflow

If you are making the same change across lots of files, prefer writing a temporary script rather than editing each file one by one yourself. Always test the script against a few files first to make sure it's working as intended. If you can't get the script working right after a couple attempts, give up and manually edit the files.

Format the code when you're done working. There's no need to rerun tests after code formatting.

## Tool Use

If the Jetbrains MCP service is available, don't use it to run Gradle or other shell commands; run those using Bash instead. But use the Jetbrains MCP service for everything other than running commands.

If the Context7 MCP service is available, use it to look up documentation for any libraries you aren't sure how to use correctly.
