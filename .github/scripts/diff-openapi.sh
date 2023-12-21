#!/bin/bash
#
# Diffs the locally-generated OpenAPI YAML schema (which must already exist)
# against the one from the staging environment so people can easily see what
# has changed in the API.
#

# Remove lines that will always differ from staging since they will just be
# noise in the diff.
ignore_noise() {
    egrep -v '^ *(version|url|openIdConnectUrl): ' "$1"
}

# Surrounds the diff output with some Markdown so it's presentable as a PR
# comment.
write_markdown_output() {
    echo "Differences between staging OpenAPI schema and schema from this PR:"
    echo
    echo '```diff'
    cat "$1"
    echo '```'
}

ignore_noise openapi.yaml > /tmp/new.yaml

if curl -s https://staging.terraware.io/v3/api-docs.yaml > /tmp/staging-raw.yaml; then
    ignore_noise /tmp/staging-raw.yaml > /tmp/staging.yaml
    if diff -u /tmp/staging.yaml /tmp/new.yaml > /tmp/openapi.diff; then
        echo "No changes."
        echo "OPENAPI_HAS_CHANGES=false" >> "$GITHUB_ENV"
    else
        write_markdown_output /tmp/openapi.diff > openapi-diff.md
        echo "OPENAPI_HAS_CHANGES=true" >> "$GITHUB_ENV"
    fi
else
    echo "Unable to fetch OpenAPI schema from staging."
fi
