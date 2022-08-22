#!/usr/bin/env python3
import argparse
import json
import requests
import sys
from client import add_terraware_args, client_from_args


def main():
    parser = argparse.ArgumentParser(
        "Update an existing accession. The input can contain just the edits; "
        + "its values will overwrite the existing ones but any fields that aren't "
        + "present in the input won't be removed from the accession."
    )
    parser.add_argument(
        "--simulate",
        "-n",
        action="store_true",
        help="Do not save edits, just show what the resulting data would have been.",
    )
    parser.add_argument(
        "--verbose",
        "-v",
        action="store_true",
        help="Show updated accession data after editing.",
    )
    parser.add_argument(
        "--version",
        "-V",
        type=int,
        help="Version number of API endpoint",
        default=1,
    )
    parser.add_argument("accessionId")
    parser.add_argument(
        "file",
        nargs="?",
        help="JSON file with edits. If not specified, JSON is read from standard input.",
    )
    add_terraware_args(parser)

    args = parser.parse_args()

    if args.file:
        with open(args.file) as fp:
            edits = json.load(fp)
    else:
        edits = json.load(sys.stdin)

    client = client_from_args(args)

    accession = client.get_accession(args.accessionId, version=args.version)

    accession.update(edits)

    updated = client.update_accession(
        args.accessionId, accession, args.simulate, args.version
    )
    if args.verbose:
        print(json.dumps(updated))
    else:
        print("OK")


if __name__ == "__main__":
    main()
