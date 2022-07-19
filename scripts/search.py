#!/usr/bin/env python3
import argparse
import json
import sys
import time

from client import add_terraware_args, client_from_args


def export_csv(criteria, client, filename):
    r = client.export_accession_search(criteria)
    with open(filename, "wb") as f:
        f.write(r.content)


def time_search(criteria, client):
    start_time = time.time()
    client.search_accessions(criteria)
    end_time = time.time()
    return end_time - start_time


def run_timing_test(criteria, client):
    total_time = 0
    runs = 100

    for i in range(0, runs):
        total_time += time_search(criteria, client)

    print(f"Did {runs} runs in {total_time} seconds, time per run {total_time / runs}")


example_criteria = {
    "fields": [
        "accessionNumber",
        "active",
        "collectedDate",
        "endangered",
        "geolocation",
        "latestViabilityPercent",
        "latestViabilityTestDate",
        "siteLocation",
        "species_scientificName",
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
                "field": "species_scientificName",
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
        "--facility",
        "-f",
        type=int,
        help="Generate accessions at this facility. Default is to pick the first seed bank "
        + "facility accessible by the user.",
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

    client = client_from_args(args)

    if args.facility:
        criteria["facilityId"] = args.facility
    else:
        criteria["facilityId"] = [
            entry["id"]
            for entry in client.list_facilities()
            if entry["type"] == "Seed Bank"
        ][0]

    if args.timing:
        run_timing_test(criteria, client)
    else:
        if args.values:
            results = client.search_accession_values(criteria)
        elif args.all_values:
            results = client.search_all_accession_values(criteria)
        else:
            results = client.search_accessions(criteria)

        if args.count:
            print(f"Got {len(results)} results")
        else:
            print(json.dumps(results))


if __name__ == "__main__":
    main()
