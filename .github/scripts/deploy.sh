#!/bin/bash

mkdir -p ~/.ssh
echo "$SSH_KEY" > ~/.ssh/key
chmod 600 ~/.ssh/key
cat >> ~/.ssh/config <<END
Host bastion*
  User $SSH_USER
  IdentityFile ~/.ssh/key
  ProxyCommand none
  StrictHostKeyChecking no

Host terraware*
  User $SSH_USER
  IdentityFile ~/.ssh/key
  ProxyCommand ssh -W %h:%p $SSH_HOST
  StrictHostKeyChecking no
END

# aws ec2 describe-instances --filters "Name=tag:Application,Values=terraware" \
#   | jq -r ' .Reservations[].Instances[].PrivateIpAddress' \

aws ec2 describe-instances --filters "Name=tag:Application,Values=terraware" \
  | jq -r ' .Reservations[].Instances[].Tags[] | select(.Key == "Hostname") | .Value' \
  | while read _host; do
      echo
      echo "Deploying to $_host"
      echo
      ssh $_host "echo Hello!"
    done
