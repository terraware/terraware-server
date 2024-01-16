#!/bin/bash
#
# Diffs the locally-generated OpenAPI YAML schema (which must already exist)
# against the one from the staging environment so people can easily see what
# has changed in the API.
#
# Sets the OPENAPI_COMMENT_MODE environment variable to either "upsert" (if
# there were API diffs) or "delete" (if the API didn't change). If there were
# diffs, they will be in the file openapi-diff.md in Markdown form suitable
# for posting as a PR comment.
#
# See also: https://github.com/marketplace/actions/comment-pull-request

DIR=/tmp/openapi-diff

mkdir "$DIR"

error_comment() {
    echo "$1"

    (
        echo "# Error checking OpenAPI diff"
        echo "$1"
    ) > "$DIR/diff.md"

    echo "OPENAPI_COMMENT_MODE=upsert" >> "$GITHUB_ENV"
}

# OpenAPI Gradle task outputs a YAML file, but the diff tool expects JSON.
python3 -c 'import json,yaml; print(json.dumps(yaml.safe_load(open("openapi.yaml"))))' > "$DIR/new.json"

if curl -s https://staging.terraware.io/v3/api-docs > "$DIR/old.json"; then
    if docker run \
        --rm \
        -v "$DIR":/specs \
        openapitools/openapi-diff \
            --markdown /specs/diff.md \
            /specs/old.json \
            /specs/new.json
    then
        # Markdown file will be empty if there are no API differences.
        if [ -s "$DIR/diff.md" ]; then
            echo "OPENAPI_COMMENT_MODE=upsert" >> "$GITHUB_ENV"
        else
            echo "OPENAPI_COMMENT_MODE=delete" >> "$GITHUB_ENV"
        fi
    else
        error_comment "Unable to diff old and new schemas."
    fi
else
    error_comment "Unable to fetch schema from staging."
fi
