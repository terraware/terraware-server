#!/usr/bin/env python3
import argparse

import example_values
from client import add_terraware_args, client_from_args


def main():
    parser = argparse.ArgumentParser("Add a set of example species to an organization")
    parser.add_argument(
        "--organization",
        "-o",
        type=int,
        help="Generate species in this organization. Default is to use the lowest-numbered "
        + "organization where the current user is an admin.",
    )
    parser.add_argument(
        "--number",
        "-n",
        type=int,
        help="Number of species to create. Default is to create all example species in "
        + "example_values.py.",
    )
    add_terraware_args(parser)

    args = parser.parse_args()
    client = client_from_args(args)

    if args.organization:
        organization_id = args.organization
    else:
        organization_id = client.get_default_organization_id()

    existing_species_names = [
        species["scientificName"] for species in client.list_species(organization_id)
    ]
    new_species_names = [
        name
        for name in example_values.TREE_SPECIES
        if name not in existing_species_names
    ]

    if not new_species_names:
        raise Exception("No new species names available")
    if args.number and args.number > len(new_species_names):
        raise Exception(f"Only {len(new_species_names)} new species names available")

    for scientific_name in new_species_names[: args.number]:
        client.create_species(
            {
                "organizationId": organization_id,
                "scientificName": scientific_name,
            }
        )


if __name__ == "__main__":
    main()
