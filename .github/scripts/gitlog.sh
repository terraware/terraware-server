#!/bin/bash

LAST_TAG=`git tag --list --sort=creatordate 'v[0-9]*' | tail -n1`

git log $LAST_TAG..HEAD --oneline > docs/unreleased.log
