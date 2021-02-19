#!/usr/bin/env python3
import requests
import time


def export_csv(criteria, server, filename):
    r = requests.post(f"{server}/api/v1/seedbank/search/export", json=criteria)
    r.raise_for_status()
    with open(filename, "wb") as f:
        f.write(r.content)


def run_search(criteria, server):
    r = requests.post(f"{server}/api/v1/seedbank/search", json=criteria)
    r.raise_for_status()
    return r.json()["results"]


def time_search(criteria):
    server = "http://localhost:8080"
    start_time = time.time()
    r = requests.post(f"{server}/api/v1/seedbank/search", json=criteria)
    end_time = time.time()
    return end_time - start_time


def run_timing_test(criteria):
    total_time = 0
    runs = 100

    for i in range(0, runs):
        total_time += time_search(criteria)

    print(f"Did {runs} runs in {total_time} seconds, time per run {total_time / runs}")


criteria = {
    "fields": [
        "accessionNumber",
        "active",
        "collectedDate",
        "endangered",
        "estimatedSeedsIncoming",
        "geolocation",
        "latestGerminationTestDate",
        "latestViabilityPercent",
        "primaryCollector",
        "rare",
        "receivedDate",
        "siteLocation",
        "species",
        "state",
        "storageCondition",
        "totalViabilityPercent",
        "treesCollectedFrom",
        "withdrawalSeeds",
        "seedsRemaining",
    ],
    "sortOrder": [{"field": "receivedDate", "direction": "Descending"}],
    "filters": [{"field": "storagePackets", "type": "Range", "values": ["1", "10"]}],
}

server = "http://localhost:8080"

print(run_search(criteria, server))
