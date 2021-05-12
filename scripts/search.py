#!/usr/bin/env python3
import argparse
import json
import requests
import sys
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


def run_values(criteria, server):
    r = requests.post(f"{server}/api/v1/seedbank/values", json=criteria)
    r.raise_for_status()
    return r.json()["results"]


def run_all_values(criteria, server):
    r = requests.post(f"{server}/api/v1/seedbank/values/all", json=criteria)
    r.raise_for_status()
    return r.json()["results"]


def time_search(criteria, server):
    start_time = time.time()
    r = requests.post(f"{server}/api/v1/seedbank/search", json=criteria)
    end_time = time.time()
    return end_time - start_time


def run_timing_test(criteria, server):
    total_time = 0
    runs = 100

    for i in range(0, runs):
        total_time += time_search(criteria, server)

    print(f"Did {runs} runs in {total_time} seconds, time per run {total_time / runs}")


example_criteria = {
    "fields": [
        "accessionNumber",
        "active",
        "collectedDate",
        "endangered",
        "geolocation",
        "latestGerminationTestDate",
        "latestViabilityPercent",
        "siteLocation",
        "species",
        "state",
        "totalViabilityPercent",
        "treesCollectedFrom",
        "withdrawalGrams",
        "remainingQuantity",
        "remainingUnits",
    ],
    "sortOrder": [{"field": "receivedDate", "direction": "Descending"}],
    "search": {
        "operation": "and",
        "children": [
            {
                "operation": "field",
                "field": "species",
                "values": ["Ficus"],
                "type": "Fuzzy",
            },
            {
                "operation": "or",
                "children": [
                    {
                        "operation": "field",
                        "field": "treesCollectedFrom",
                        "type": "Range",
                        "values": ["1", "3"],
                    },
                    {
                        "operation": "field",
                        "field": "treesCollectedFrom",
                        "type": "Range",
                        "values": ["4", "6"],
                    },
                ],
            },
        ],
    },
    "count": 30,
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--all-values",
        "-a",
        action="store_true",
        help="List all values for a set of fields",
    )
    parser.add_argument(
        "--count",
        "-c",
        action="store_true",
        help="Show count of search results rather than raw results",
    )
    parser.add_argument(
        "--print-example",
        action="store_true",
        help="Output an example search payload and exit",
    )
    parser.add_argument(
        "--server",
        "-s",
        default="http://localhost:8080",
        help="URL of server to connect to",
    )
    parser.add_argument(
        "--timing",
        action="store_true",
        help="Run search repeatedly and report how long it took",
    )
    parser.add_argument(
        "--values",
        "-v",
        action="store_true",
        help="List values matching search criteria",
    )
    parser.add_argument(
        "file",
        nargs="?",
        help="Read request payload from file; use '-' for standard input",
    )

    args = parser.parse_args()

    if args.print_example:
        print(json.dumps(example_criteria, indent=2))
        return

    if args.file is not None:
        if args.file == "-":
            criteria = json.load(sys.stdin)
        else:
            with open(args.file) as fp:
                criteria = json.load(fp)
    else:
        criteria = example_criteria

    if args.timing:
        run_timing_test(criteria, args.server)
    else:
        if args.values:
            results = run_values(criteria, args.server)
        elif args.all_values:
            results = run_all_values(criteria, args.server)
        else:
            results = run_search(criteria, args.server)

        if args.count:
            print(f"Got {len(results)} results")
        else:
            print(json.dumps(results))


if __name__ == "__main__":
    main()
