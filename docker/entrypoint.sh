#!/bin/bash
#
# Configure the location of photo storage when running under Balena.
#
set -e -o pipefail

if [ "$BALENA" = 1 -a -z "$TERRAWARE_PHOTO_DIR" ]; then
    case "$TERRAWARE_USE_INTERNAL_STORAGE" in
        1|y*|t*|Y*|T*)
            VOLUME=/file-storage-volume
            if [ -d "$VOLUME" ]; then
                TERRAWARE_PHOTO_DIR="$VOLUME/photos"
            else
                echo "TERRAWARE_USE_INTERNAL_STORAGE requires binding a volume to $VOLUME"
                exit 1
            fi
            ;;

        *)
            TERRAWARE_PHOTO_DIR="/run/log/journal/terraware/photos"
            if [ ! -d "$TERRAWARE_PHOTO_DIR" ]; then
                echo "$TERRAWARE_PHOTO_DIR not found; will restart so disk manager can create it"
                sleep 10
                exit 1
            fi
            ;;
    esac
fi

if [ -n "$TERRAWARE_PHOTO_DIR" ]; then
    exec /usr/bin/env TERRAWARE_PHOTO_DIR="$TERRAWARE_PHOTO_DIR" "$@"
else
    exec "$@"
fi
