#!/bin/bash

mkdir -p ~/.ssh
echo "$SSH_KEY" > ~/.ssh/key
chmod 600 ~/.ssh/key
cat >> ~/.ssh/config <<END
Host $SSH_HOST
  User $SSH_USER
  IdentityFile ~/.ssh/key
  StrictHostKeyChecking no
END
  # ProxyCommand none
# Host terraware*
#   User $SSH_USER
#   IdentityFile ~/.ssh/key
#   ProxyCommand ssh -W %h:%p -q $SSH_HOST
#   StrictHostKeyChecking no

# aws ec2 describe-instances --filters "Name=tag:Application,Values=terraware" \
#   | jq -r ' .Reservations[].Instances[].PrivateIpAddress' \

aws ec2 describe-instances --filters "Name=tag:Application,Values=terraware" \
  | jq -r ' .Reservations[].Instances[].Tags[] | select(.Key == "Hostname") | .Value' \
  | while read _host; do
      echo
      echo "Deploying to $_host"
      echo
      ssh -J $SSH_HOST $_host "/usr/local/bin/update.sh terraware-server $COMMIT_SHA"
      # ssh $_host /usr/local/bin/update.sh
    done
