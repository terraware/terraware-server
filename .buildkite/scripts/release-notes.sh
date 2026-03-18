#!/bin/bash
#
# Generate changelog, post to Slack, and transition Jira issues.
# Replaces the release-notes and Jira steps from GHA main.yml.

set -euo pipefail

.buildkite/scripts/install-deps.sh --tools

# Get version tags
git fetch --tags --depth=1
THIS_VERSION=$(git tag --list --sort=creatordate 'v[0-9]*' | tail -n1)
LAST_VERSION=$(git tag --list --sort=creatordate 'v[0-9]*' | tail -n2 | head -n1)

# Deepen fetch until we find the last version tag
MAX_DEPTH=500
STEP=50
CURRENT_DEPTH=$STEP

echo "--- :slack: Generating release notes"

while true; do
    echo "Fetching with depth=$CURRENT_DEPTH"
    git fetch --tags --depth=$CURRENT_DEPTH

    if git rev-parse "$LAST_VERSION" >/dev/null 2>&1; then
        echo "Found last version $LAST_VERSION"
        break
    fi

    if [ "$CURRENT_DEPTH" -ge "$MAX_DEPTH" ]; then
        echo "Could not find last version $LAST_VERSION"
        exit 1
    fi

    CURRENT_DEPTH=$((CURRENT_DEPTH + STEP))
done

CHANGELOG=$(git log "$LAST_VERSION".."$THIS_VERSION" --pretty=format:"%s")

# Post release notes to Slack
if [[ -n "${SLACK_WEBHOOK_URL:-}" ]]; then
    # Escape the changelog for JSON
    CHANGELOG_JSON=$(echo "$CHANGELOG" | jq -Rs .)

    curl -s -X POST "$SLACK_WEBHOOK_URL" \
        -H 'Content-Type: application/json' \
        -d "{
            \"release_notes\": $CHANGELOG_JSON,
            \"release_repository\": \"${BUILDKITE_REPO:-terraware/terraware-server}\",
            \"release_version\": \"$THIS_VERSION\"
        }"
    echo "Posted release notes to Slack."
else
    echo "SLACK_WEBHOOK_URL not set, skipping Slack notification."
fi

# Transition Jira issues
if [[ -n "${JIRA_BASE_URL:-}" && -n "${JIRA_USER_EMAIL:-}" && -n "${JIRA_API_TOKEN:-}" ]]; then
    # Fetch the unreleased log from GitHub Pages and extract Jira issue keys
    JIRA_ISSUES=$(curl -s https://terraware.github.io/terraware-server/unreleased.log |
        grep -Eo 'SW-[0-9]+' |
        sort -u)

    JIRA_AUTH=$(echo -n "${JIRA_USER_EMAIL}:${JIRA_API_TOKEN}" | base64)

    for issue in $JIRA_ISSUES; do
        echo "Transitioning $issue..."
        # Get available transitions
        TRANSITIONS=$(curl -s \
            -H "Authorization: Basic $JIRA_AUTH" \
            -H "Content-Type: application/json" \
            "${JIRA_BASE_URL}/rest/api/3/issue/${issue}/transitions")

        # Find the transition ID for "Released to Production from Done"
        TRANSITION_ID=$(echo "$TRANSITIONS" | jq -r '.transitions[] | select(.name == "Released to Production from Done") | .id')

        if [[ -n "$TRANSITION_ID" ]]; then
            curl -s -X POST \
                -H "Authorization: Basic $JIRA_AUTH" \
                -H "Content-Type: application/json" \
                -d "{\"transition\": {\"id\": \"$TRANSITION_ID\"}}" \
                "${JIRA_BASE_URL}/rest/api/3/issue/${issue}/transitions"
            echo "Transitioned $issue"
        else
            echo "No matching transition found for $issue, skipping."
        fi
    done
else
    echo "Jira credentials not configured, skipping Jira transitions."
fi
