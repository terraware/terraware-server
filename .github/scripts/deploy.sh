#!/bin/bash

mkdir -p ~/.ssh
echo "$SSH_KEY" > ~/.ssh/key
chmod 600 ~/.ssh/key
echo "$SSH_CONFIG" > ~/.ssh/config

aws ec2 describe-instances --filters "Name=tag:Application,Values=terraware" \
  | jq -r ' .Reservations[].Instances[].Tags[] | select(.Key == "Hostname") | .Value' \
  | while read _host; do
      echo
      echo "Deploying to $_host"
      echo
      ssh -n $_host "/usr/local/bin/update.sh terraware-server $COMMIT_SHA"
    done
