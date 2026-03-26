# NOTES ABOUT THIS PIPELINE

## Uploading

The pipeline file here doesn't do anything in and of itself; it needs to be uploaded using the
Buildkite agent. On the Buildkite side, in the repo configuration, we define the starting point
of the pipeline; the step defined here is the thing that uploads `pipeline.yml` to Buildkite.

```yaml
env:
  BUILDKITE_PLUGIN_S3_CACHE_BUCKET: "terraformation-buildkite-cache"
  BUILDKITE_PLUGIN_S3_CACHE_ONLY_SHOW_ERRORS: "true"
  BUILDKITE_PLUGIN_S3_CACHE_PREFIX: "repo/terraware-server"

agents:
  queue: "x86-8core"

steps:
  - label: ":pipeline: Upload pipeline"
    plugins:
      - peakon/git-shallow-clone#v0.0.1:
          # Depth should be large enough that it will find the commit on the main branch
          # from which the current branch was forked, but small enough to keep the clone
          # operation reasonably fast.
          depth: 200
    command: "buildkite-agent pipeline upload"
```

## YAML anchors

We define YAML anchors for elements that are used in several places in the pipeline. The "common"
keyword isn't interpreted by Buildkite; it's just used as a place to put the anchors. Anchors can
also be defined inline in steps, but to make the code easier to follow, only do that if you're
not reusing the anchor in other steps.

The Buildkite docs have a brief introduction to YAML anchors, if you're not familiar with them:
https://buildkite.com/docs/pipelines/integrations/plugins/using#using-yaml-anchors-with-plugins

## Scripts

Most of the steps in the build have a single "command" value that runs a shell script in the
.buildkite/scripts directory. The script then performs the actual actions (compiling, running
tests, etc.) While it's possible to pull that logic up into the pipeline, keeping the logic in
scripts lets us add whatever kinds of conditional logic we need. Shell scripts can also be linted
and IDEs can do syntax highlighting and refactoring.

You'll notice the scripts have a bunch of "echo" commands that output lines starting with "---".
Those lines mark the start of collapsible sections. More information here:
https://buildkite.com/docs/pipelines/configure/managing-log-output

## Caching and artifacts

We run Buildkite on ephemeral hosts that go away when they're idle. In addition, each step in a
Buildkite pipeline runs in a clean copy of the checked-out repo (possibly on a different host).
We therefore can't rely on the local filesystem for caching dependencies or for making build
output available to later steps.

To deal with this, we use two Buildkite features: caches and artifacts.

Caches persist between jobs and are keyed by a checksum of a list of manifest files. We use caches
for downloaded dependencies. We use our own S3 bucket to store the caches.

Caches have a concept of a level hierarchy, documented here:
https://buildkite.com/resources/plugins/buildkite-plugins/cache-buildkite-plugin/#caching-levels
In this pipeline, all builds read dependencies from the pipeline level and write dependencies
to the branch and file levels. Then, after a successful build, if we're building on the "main"
branch, we "promote" the caches to the pipeline level so they'll be available in other branches.

Artifacts are per-job; we use them to pass data between steps. For example, the "build" directory
gets bundled up in a compressed tarfile so it can be downloaded by later steps.

## Build environment

As currently configured, our Buildkite builds run on EC2 instances in a dedicated VPC. The
instances are running Amazon Linux 2023, and the build steps run directly on the host, not in
Docker containers. That means you need to make sure you've installed the tools you need for a
given build step, and you should try to avoid creating files outside the repo directory since
they won't necessarily be cleaned up if another build step runs on the same host.

System setup is mostly encapsulated in .buildkite/scripts/install-deps.sh.

## Concurrency or the lack thereof

To minimize costs, we don't currently run any of the steps in this pipeline in parallel, meaning
we don't need to spin up multiple hosts to run a build. Linearity is enforced via "depends_on"
directives in the build steps.

Note that just because the steps are linear, there's no guarantee Buildkite will run them all
on the same host. If there are multiple hosts available, any of them can be chosen to execute
a step. In practice, steps usually do stick to the same host, but that's not guaranteed and
shouldn't be relied upon.
