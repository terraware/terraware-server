#!/usr/bin/env python3
import argparse
import json
import requests
import sys


def main():
    parser = argparse.ArgumentParser(
        "Update an existing accession. The input can contain just the edits; "
        + "its values will overwrite the existing ones but any fields that aren't "
        + "present in the input won't be removed from the accession."
    )
    parser.add_argument(
        "--server",
        "-s",
        default="http://localhost:8080",
        help="Base URL of seedbank-server.",
    )
    parser.add_argument(
        "--verbose",
        "-v",
        action="store_true",
        help="Show updated accession data after editing.",
    )
    parser.add_argument("accessionNumber")
    parser.add_argument(
        "file",
        nargs="?",
        help="JSON file with edits. If not specified, JSON is read from standard input.",
    )

    args = parser.parse_args()

    if args.file:
        with open(args.file) as fp:
            edits = json.load(fp)
    else:
        edits = json.load(sys.stdin)

    uri = f"{args.server}/api/v1/seedbank/accession/{args.accessionNumber}"

    accession = requests.get(uri).json()
    accession.update(edits)

    r = requests.put(uri, json=accession)
    r.raise_for_status()

    if args.verbose:
        print(json.dumps(r.json()["accession"]))
    else:
        print(r.json()["status"])


if __name__ == "__main__":
    main()
