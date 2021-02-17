#!/usr/bin/env python3
import requests
import time


def export_csv(criteria, server, filename):
    r = requests.post(f"{server}/api/v1/seedbank/search/export", json=criteria)
    r.raise_for_status()
    with open(filename, "wb") as f:
        f.write(r.content)


def time_search(criteria):
    server = "http://localhost:8080"
    start_time = time.time()
    r = requests.post(f"{server}/api/v1/seedbank/search", json=criteria)
    end_time = time.time()
    return end_time - start_time


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
    "filters": [{"field": "accessionNumber", "type": "Fuzzy", "values": ["A"]}],
}

total_time = 0
runs = 100

for i in range(0, runs):
    total_time += time_search(criteria)

print(f"Did {runs} runs in {total_time} seconds, time per run {total_time / runs}")
