#!/usr/bin/env python3
import argparse
import json
import sys
import time

from client import add_terraware_args, client_from_args


def export_csv(payload, client, filename):
    r = client.export_search(payload)
    with open(filename, "wb") as f:
        f.write(r.content)


def time_search(payload, client):
    start_time = time.time()
    client.search(payload)
    end_time = time.time()
    return end_time - start_time


def run_timing_test(payload, client):
    total_time = 0
    runs = 100

    for _ in range(0, runs):
        total_time += time_search(payload, client)

    print(f"Did {runs} runs in {total_time} seconds, time per run {total_time / runs}")


example_payload = {
    "prefix": "facilities.accessions",
    "fields": [
        "accessionNumber",
        "active",
        "collectedDate",
        "geolocation",
        "plantsCollectedFrom",
        "species_endangered",
        "species_scientificName",
        "state",
        "totalViabilityPercent",
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
                "field": "species_scientificName",
                "values": ["Ficus"],
                "type": "Fuzzy",
            },
            {
                "operation": "or",
                "children": [
                    {
                        "operation": "field",
                        "field": "plantsCollectedFrom",
                        "type": "Range",
                        "values": ["1", "3"],
                    },
                    {
                        "operation": "field",
                        "field": "plantsCollectedFrom",
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
    add_terraware_args(parser)

    args = parser.parse_args()

    if args.print_example:
        print(json.dumps(example_payload, indent=2))
        return

    if args.file is not None:
        if args.file == "-":
            payload = json.load(sys.stdin)
        else:
            with open(args.file) as fp:
                payload = json.load(fp)
    else:
        payload = example_payload

    client = client_from_args(args)

    if args.timing:
        run_timing_test(payload, client)
    else:
        if args.values:
            results = client.search_accession_values(payload)
        elif args.all_values:
            results = client.search_all_accession_values(payload)
        else:
            results = client.search(payload)

        if args.count:
            print(f"Got {len(results)} results")
        else:
            print(json.dumps(results))


if __name__ == "__main__":
    main()
