#!/usr/bin/env python3
import argparse
from datetime import date, timedelta
import json
import random
from random import randint
from typing import List, Optional
from client import TerrawareClient, add_terraware_args, client_from_args


def generate_notes() -> Optional[str]:
    very = " ".join(["very" for _ in range(0, randint(10, 200))])
    return random.choice(
        [
            None,
            None,
            None,
            "Some notes to start things off",
            "This seedling is fantastic",
            "This seedling is beautiful",
            "This seedling makes me want to sing",
            "So. Many. Aphids.",
            f"This is a {very} long note.",
        ]
    )


def generate_recent_date() -> Optional[date]:
    return date.today() - timedelta(days=randint(0, 90))


def generate_upcoming_date() -> Optional[date]:
    return date.today() + timedelta(days=randint(0, 90))


def create_batch(client: TerrawareClient, facility_id: int, species_ids: List[int]):
    create_payload = {
        "activeGrowthQuantity": randint(1, 20),
        "addedDate": str(generate_recent_date()),
        "facilityId": facility_id,
        "germinatingQuantity": randint(1, 10) if randint(0, 3) == 0 else 0,
        "notes": generate_notes(),
        "readyByDate": str(generate_upcoming_date()) if randint(0, 1) == 0 else None,
        "readyQuantity": randint(1, 20),
        "speciesId": random.choice(species_ids),
    }

    return client.create_seedling_batch(create_payload)


def main():
    parser = argparse.ArgumentParser("Create random seedling batches")
    parser.add_argument(
        "--facility",
        "-f",
        type=int,
        help="Generate batches at this facility. Default is to pick the first nursery "
             + "facility accessible by the user.",
    )
    parser.add_argument(
        "--number", "-n", type=int, default=10, help="Number of batches to create."
    )
    parser.add_argument(
        "--verbose",
        "-v",
        action="store_true",
        help="Show populated batch data as returned by the server.",
    )
    add_terraware_args(parser)
    args = parser.parse_args()

    client = client_from_args(args)

    if args.facility:
        facility_id = args.facility
    else:
        nurseries = [
            entry["id"]
            for entry in client.list_facilities()
            if entry["type"] == "Nursery"
        ]
        if not nurseries:
            raise Exception("No nursery facilities found.")
        facility_id = nurseries[0]

    organization_id = client.get_facility(facility_id)["organizationId"]

    species_ids = [species["id"] for species in client.list_species(organization_id)]
    if not species_ids:
        raise Exception("No species are defined for organization.")

    for n in range(0, args.number):
        batch = create_batch(client, facility_id, species_ids)
        if args.verbose:
            print(json.dumps(batch, indent=2))
        else:
            print(f"{batch['id']} {batch['batchNumber']}")


if __name__ == "__main__":
    main()
