#!/bin/bash
SERVICE_NAME=Photos
DATA_PATH=/data/.

post_metric() {
    curl -sSf \
        --data device=server \
        --data-urlencode "timeseries=$1" \
        --data "value=$2" \
        --data "units=$3" \
        --data "siteModuleId=${TERRAWARE_SITE_MODULE_ID}" \
        http://seedbank-server:8080/api/v1/seedbank/timeseries \
        > /dev/null
}

block_size=$(stat -fc %S "${DATA_PATH}")
blocks_per_mb=$(( 1024 * 1024 / $block_size ))
total_blocks=$(stat -fc %b "${DATA_PATH}")
total_mb=$(( total_blocks / blocks_per_mb ))

until post_metric "Total Space (${SERVICE_NAME})" "$total_mb" MB; do
    sleep 10
done

while true; do
    free_blocks=$(stat -fc %a /data/.)
    free_mb=$(( free_blocks / blocks_per_mb ))

    if post_metric "Free Space (${SERVICE_NAME})" "$free_mb" MB; then
        sleep 300
    else
        sleep 10
    fi
done
