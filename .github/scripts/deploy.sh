#!/bin/bash

mkdir -p ~/.ssh
echo "$SSH_KEY" > ~/.ssh/key
chmod 600 ~/.ssh/key
cat >> ~/.ssh/config <<END
Host bastion
  HostName $SSH_HOST
  User $SSH_USER
  IdentityFile ~/.ssh/key
  StrictHostKeyChecking no
END

aws ec2 describe-instances --filters "Name=tag:Application,Values=terraware" \
  | jq -r ' .Reservations[].Instances[].PrivateIpAddress' \
  | while read _ip; do
      echo
      echo "Deploying to $_ip"
      echo
      ssh -A bastion ssh $_ip <<-EOF
      sed -ir "s/(SERVER_COMMIT_SHA)=.*/\1=$COMMIT_SHA/g" ~/terraware/.env
      /usr/local/bin/update.sh terraware-server
EOF
    done
