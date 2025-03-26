#!/usr/bin/env python3
"""
Withdraw plants from a nursery to each of the subzones in a planting site.

Ensures that all the subzones of a newly-created site are planted.
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
        "batchId": batch_id,
        "notReadyQuantityWithdrawn": 0,
        "readyQuantityWithdrawn": 1,
    }


def generate_nursery_withdrawal(
    facility_id, batch_id, planting_site_id, planting_subzone_id
):
    return {
        "batchWithdrawals": [generate_batch_withdrawal(batch_id)],
        "facilityId": facility_id,
        "plantingSiteId": planting_site_id,
        "plantingSubzoneId": planting_subzone_id,
        "purpose": "Out Plant",
        "withdrawnDate": str(date.today()),
    }


def main():
    parser = argparse.ArgumentParser(
        "Withdraw one plant from a nursery to each subzone at a planting site"
    )
    parser.add_argument("--batch", "-b", type=int, help="Batch ID to withdraw from.")
    parser.add_argument(
        "--planting-site", "-p", type=int, help="Planting site ID to deliver to."
    )
    add_terraware_args(parser)
    args = parser.parse_args()

    client = client_from_args(args)

    batch = client.get_seedling_batch(args.batch)
    planting_site = client.get_planting_site(args.planting_site, depth="Subzone")

    for planting_zone in planting_site["plantingZones"]:
        for planting_subzone in planting_zone["plantingSubzones"]:
            print(f"Withdrawing to subzone {planting_subzone['id']}")
            client.withdraw_seedling_batch(
                generate_nursery_withdrawal(
                    batch["facility_id"],
                    args.batch,
                    args.planting_site,
                    planting_subzone["id"],
                )
            )


if __name__ == "__main__":
    main()
