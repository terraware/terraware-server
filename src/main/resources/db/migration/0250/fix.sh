#!/bin/zsh

for entry in ./*; do
    version=$(echo $entry | sed 's/[^0-9]*//g')
    if [[ -z "$version" ]]; then
        continue ;
    fi

    if (( $version < 279 )); then
        continue;
    fi

    echo $version
    ((version++))

    echo "new version $version"
    newEntry=$(echo $entry | sed -r "s/[0-9]+/$version/g")

    echo "$entry -> $newEntry"

    mv $entry $newEntry
done
