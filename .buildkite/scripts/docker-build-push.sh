#!/usr/bin/env bash

set -euo pipefail

.buildkite/scripts/install-deps.sh --java --tools

# Use a unique name so we don't collide with other agents if we're running multiple agents on
# the same runner host.
builder_name="builder-${BUILDKITE_BUILD_ID}"

# Clean up the builder when this script exits.
cleanup_builder() {
    local exit_code=$?

    echo "~~~ :docker: Clean up builder"

    docker buildx rm "$builder_name"
    exit "$exit_code"
}

echo "--- :gradle: Extract Docker image layers"

make -C docker prepare

echo "~~~ :docker: Install QEMU"

# https://docs.docker.com/build/building/multi-platform/#qemu
docker run --privileged --rm tonistiigi/binfmt --install all

echo "~~~ :docker: Set up builder"

docker buildx create --name "$builder_name" --driver docker-container
trap cleanup_builder EXIT

if [[ "$IS_CD" == true ]]; then
    echo "--- :docker: Log into Docker Hub"

    docker login -u "$DOCKERHUB_USERNAME" -p "$DOCKERHUB_TOKEN"

    echo "--- :docker: Build and push Docker image"

    docker_image="${TERRAWARE_SERVER_DOCKER_IMAGE:-terraware/terraware-server}"
    docker_tags="-t ${docker_image}:$COMMIT_SHA -t ${docker_image}:${TIER}"
    push_option="--push"
else
    echo "--- :docker: Build Docker image"

    docker_tags="-t test"
    push_option=
fi

cd build/docker

# shellcheck disable=SC2086
docker buildx build \
        --builder="$builder_name" \
        --cache-from type=local,src="${BUILDKITE_BUILD_CHECKOUT_PATH}/.buildx-cache" \
        --cache-to type=local,mode=max,dest="${BUILDKITE_BUILD_CHECKOUT_PATH}/.buildx-cache-new" \
        --platform linux/amd64,linux/arm64 \
        --progress quiet \
        $push_option \
        $docker_tags \
        .

echo "~~~ :docker: Use build cache from this build for subsequent builds"

cd "${BUILDKITE_BUILD_CHECKOUT_PATH}"
rm -rf .buildx-cache
mv .buildx-cache-new .buildx-cache
