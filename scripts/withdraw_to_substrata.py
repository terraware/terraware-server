#!/usr/bin/env python3
"""
Withdraw plants from a nursery to each of the substrata in a planting site.

Ensures that all the substrata of a newly-created site are planted.
"""

import argparse
from datetime import date, datetime, timezone
import json
import random
import time
from client import add_terraware_args, client_from_args


def isoformat(timestamp: int) -> str:
    return (
        datetime.fromtimestamp(timestamp, timezone.utc)
        .isoformat()
        .replace("+00:00", "Z")
    )


def generate_batch_withdrawal(batch_id):
    return {
        "activeGrowthQuantityWithdrawn": 0,
        "batchId": batch_id,
        "readyQuantityWithdrawn": 1,
    }


def generate_nursery_withdrawal(facility_id, batch_id, planting_site_id, substratum_id):
    return {
        "batchWithdrawals": [generate_batch_withdrawal(batch_id)],
        "facilityId": facility_id,
        "plantingSiteId": planting_site_id,
        "substratumId": substratum_id,
        "purpose": "Out Plant",
        "withdrawnDate": str(date.today()),
    }


def main():
    parser = argparse.ArgumentParser(
        "Withdraw one plant from a nursery to each substratum at a planting site"
    )
    parser.add_argument("--batch", "-b", type=int, help="Batch ID to withdraw from.")
    parser.add_argument(
        "--planting-site", "-p", type=int, help="Planting site ID to deliver to."
    )
    add_terraware_args(parser)
    args = parser.parse_args()

    client = client_from_args(args)

    batch = client.get_seedling_batch(args.batch)
    planting_site = client.get_planting_site(args.planting_site, depth="Substratum")

    for stratum in planting_site["strata"]:
        for substratum in stratum["substrata"]:
            print(f"Withdrawing to substratum {substratum['id']}")
            client.withdraw_seedling_batch(
                generate_nursery_withdrawal(
                    batch["facilityId"],
                    args.batch,
                    args.planting_site,
                    substratum["id"],
                )
            )


if __name__ == "__main__":
    main()
