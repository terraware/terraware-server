#!/bin/bash

mkdir -p ~/.ssh
echo "$SSH_KEY" > ~/.ssh/key
chmod 600 ~/.ssh/key
cat >> ~/.ssh/config <<END
Host $SSH_HOST
  User $SSH_USER
  IdentityFile ~/.ssh/key
  StrictHostKeyChecking no
  ProxyCommand none

Host *
  User $SSH_USER
  IdentityFile ~/.ssh/key
  ProxyCommand ssh -W %h:%p -q $SSH_HOST
  StrictHostKeyChecking no
END

aws ec2 describe-instances --filters "Name=tag:Application,Values=terraware" \
  | jq -r ' .Reservations[].Instances[].PrivateIpAddress' \
  | while read _ip; do
      echo
      echo "Deploying to $_ip"
      echo
      # ssh -A bastion ssh $_ip "/usr/local/bin/update.sh terraware-server $COMMIT_SHA"
      ssh $_ip /usr/local/bin/update.sh
    done
