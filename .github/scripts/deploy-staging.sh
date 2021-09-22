#!/bin/bash

mkdir -p ~/.ssh
echo "$SSH_KEY" > ~/.ssh/staging.key
chmod 600 ~/.ssh/staging.key
cat >> ~/.ssh/config <<END
Host bastion
  HostName $SSH_HOST
  User $SSH_USER
  IdentityFile ~/.ssh/staging.key
  StrictHostKeyChecking no
END

aws ec2 describe-instances --filters "Name=tag:Application,Values=terraware" \
  | jq -r ' .Reservations[].Instances[].PrivateIpAddress' \
  | while read _ip; do
      echo
      echo "Deploying to $_ip"
      echo
      ssh -A bastion ssh $_ip /usr/local/bin/update.sh
    done
