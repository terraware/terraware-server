#!/bin/bash
#
# Set up the filesystem for file (photo) storage when running under Balena.
#

set -e -o pipefail

if [ "$BALENA" = 1 ]; then
    case "$TERRAWARE_USE_INTERNAL_STORAGE" in
        1|y*|t*|Y*|T*)
            if [ -d /data ]; then
                rmdir /data
            fi
            if [ -L /data ]; then
                rm /data
            fi
            ln -s /file-storage-volume /data
            ;;

        *)
            DISK_DEVICE="${TERRAWARE_FILE_STORAGE_DISK:-/dev/md0p3}"
            if [ -L /data ]; then
                rm /data
            fi
            mkdir -p /data
            mount "$DISK_DEVICE" /data
            ;;
    esac

    /usr/local/bin/metrics.sh &
fi

exec "$@"
