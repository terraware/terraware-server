#!/usr/bin/env python3
import argparse
import random
import time
from datetime import datetime, timezone

from client import add_terraware_args, client_from_args


def isoformat(timestamp: int) -> str:
    """Convert a Unix timestamp to ISO 8601 format with Z suffix."""
    return (
        datetime.fromtimestamp(timestamp, timezone.utc)
        .isoformat()
        .replace("+00:00", "Z")
    )


def random_decimal(min_val, max_val, precision=1):
    """Generate a random decimal with specified precision."""
    return round(random.uniform(min_val, max_val), precision)


def generate_species_entry(species_ids):
    """Generate a random species entry with either ID or name."""
    if random.randint(1, 10) > 2:  # 80% chance of known species
        return {
            "commonName": None,
            "isInvasive": random.choice([True, False]),
            "isThreatened": random.choice([True, False]),
            "scientificName": None,
            "speciesId": random.choice(species_ids),
        }
    else:
        return {
            "commonName": None,
            "isInvasive": random.choice([True, False]),
            "isThreatened": random.choice([True, False]),
            "scientificName": f"Unknown Species {random.randint(1, 10)}",
            "speciesId": None,
        }


def get_species_refs(species_entry):
    """Extract speciesId and speciesName from a species entry."""
    if species_entry["speciesId"] is not None:
        return {"speciesId": species_entry["speciesId"], "speciesName": None}
    else:
        return {"speciesId": None, "speciesName": species_entry["scientificName"]}


def generate_unique_species_list(species_ids, num_species=None):
    """Generate a unique list of random species entries."""
    if num_species is None:
        num_species = random.randint(5, 15)

    unique_species_keys = set()
    master_species_list = []

    while len(master_species_list) < num_species:
        species_entry = generate_species_entry(species_ids)
        key = (
            species_entry["speciesId"]
            if species_entry["speciesId"] is not None
            else species_entry["scientificName"]
        )

        if key is not None and key not in unique_species_keys:
            unique_species_keys.add(key)
            master_species_list.append(species_entry)

    return master_species_list


def generate_quadrats(master_species_list):
    """Generate random quadrats with species from the master list."""
    quadrats = []
    positions = [
        "NorthwestCorner",
        "NortheastCorner",
        "SoutheastCorner",
        "SouthwestCorner",
    ]

    for position in positions:
        if random.random() < 0.9:  # 90% chance of including each position
            # Select a random subset of species for this quadrat
            max_species = min(5, len(master_species_list))
            num_quadrat_species = random.randint(1, max_species)
            selected_species = random.sample(master_species_list, num_quadrat_species)

            # Create species entries for the quadrat
            quadrat_species = [
                {"abundancePercent": random.randint(5, 95), **get_species_refs(species)}
                for species in selected_species
            ]

            quadrats.append(
                {
                    "description": f"Quadrat at {position}",
                    "position": position,
                    "species": quadrat_species,
                }
            )

    return quadrats


def generate_trees(master_species_list, num_trees=None):
    """Generate random trees using species from the master list."""
    if num_trees is None:
        num_trees = random.randint(3, 15)

    trees = []
    for _ in range(num_trees):
        species = random.choice(master_species_list)
        species_refs = get_species_refs(species)

        if random.choice(["shrub", "tree"]) == "shrub":
            trees.append(
                {
                    "growthForm": "shrub",
                    "description": f"Shrub {random.randint(1, 100)}",
                    "isDead": random.choices([True, False], weights=[1, 9])[0],
                    "shrubDiameter": random.randint(5, 50),
                    **species_refs,
                }
            )
        else:
            num_trunks = random.choices([1, random.randint(2, 5)], weights=[8, 2])[0]
            trunks = [
                {
                    "diameterAtBreastHeight": random_decimal(5, 100),
                    "height": random_decimal(1, 20),
                    "pointOfMeasurement": random_decimal(1.3, 1.5),
                    "description": f"Trunk {i+1}",
                    "isDead": random.choices([True, False], weights=[1, 9])[0],
                }
                for i in range(num_trunks)
            ]

            trees.append({"growthForm": "tree", "trunks": trunks, **species_refs})

    return trees


def generate_biomass_measurements(
    forest_type, master_species_list, quadrats, trees, observed_time=None
):
    """Generate the biomass measurements part of the payload."""
    measurements = {
        "description": f"Observation {datetime.now().strftime('%Y-%m-%d')}",
        "forestType": forest_type,
        "herbaceousCoverPercent": random.randint(10, 90),
        "smallTreeCountLow": random.randint(5, 20),
        "smallTreeCountHigh": random.randint(25, 50),
        "soilAssessment": random.choice(
            [
                "Healthy with good moisture",
                "Dry and compacted",
                "Rich in organic matter",
                "Sandy with poor nutrients",
                "Clay-like with moderate drainage",
            ]
        ),
        "quadrats": quadrats,
        "species": master_species_list,
        "trees": trees,
    }

    # Add mangrove-specific fields if needed
    if forest_type == "Mangrove" and observed_time is not None:
        measurements.update(
            {
                "ph": random_decimal(6.0, 8.5),
                "salinity": random_decimal(15, 35),
                "tide": random.choice(["High", "Low"]),
                "tideTime": observed_time,
                "waterDepth": random.randint(10, 100),
            }
        )

    return measurements


def generate_conditions():
    # Observable conditions from the enum
    observable_conditions = [
        "AnimalDamage",
        "FastGrowth",
        "FavorableWeather",
        "Fungus",
        "Pests",
        "SeedProduction",
        "UnfavorableWeather",
    ]

    return random.sample(observable_conditions, k=random.randint(1, 3))


def generate_biomass_observation_payload(planting_site_id, forest_type, species_ids):
    """Generate a random biomass observation payload."""
    # Create timestamp for the observation
    current_time = int(time.time())
    observed_time = isoformat(current_time - random.randint(0, 86400))

    # Generate components of the observation
    master_species_list = generate_unique_species_list(species_ids)
    quadrats = generate_quadrats(master_species_list)
    trees = generate_trees(master_species_list)

    # Create the payload
    payload = {
        "biomassMeasurements": generate_biomass_measurements(
            forest_type, master_species_list, quadrats, trees, observed_time
        ),
        "observationType": "Biomass Measurements",
        "plantingSiteId": planting_site_id,
        "observedTime": observed_time,
        "swCorner": {
            "type": "Point",
            "coordinates": [random.uniform(-180, 180), random.uniform(-90, 90)],
        },
        "conditions": generate_conditions(),
        "notes": f"Biomass observation on {datetime.now().strftime('%Y-%m-%d')}",
    }

    return payload


def generate_recorded_plant(species_ids):
    if random.randint(1, 10) > 1:
        certainty = "Known"
        species_id = random.choice(species_ids)
        species_name = None
    elif random.randint(1, 2) == 1:
        certainty = "Other"
        species_id = None
        species_name = f"Other {random.randint(1, 5)}"
    else:
        certainty = "Unknown"
        species_id = None
        species_name = None

    status = "Live" if random.randint(1, 5) > 1 else random.choice(["Dead", "Existing"])

    return {
        "certainty": certainty,
        "gpsCoordinates": {"type": "Point", "coordinates": [1, 2]},
        "speciesId": species_id,
        "speciesName": species_name,
        "status": status,
    }


def generate_monitoring_observation_payload(planting_site_id, species_ids):
    num_plants = random.randint(25, 200)
    observed_time = isoformat(int(time.time()) - random.randint(0, 86400))

    payload = {
        "plants": [generate_recorded_plant(species_ids) for _ in range(0, num_plants)],
        "observationType": "Monitoring",
        "plantingSiteId": planting_site_id,
        "observedTime": observed_time,
        "swCorner": {
            "type": "Point",
            "coordinates": [random.uniform(-180, 180), random.uniform(-90, 90)],
        },
        "conditions": generate_conditions(),
        "notes": f"Monitoring observation on {datetime.now().strftime('%Y-%m-%d')}",
    }

    return payload


def main():
    parser = argparse.ArgumentParser("Complete ad-hoc observations")
    parser.add_argument(
        "--organization",
        "-O",
        type=int,
        help="Choose from this organization. Default is the current user's lowest-numbered organization.",
    )
    parser.add_argument(
        "--site",
        "-s",
        type=int,
        help="Record results for this planting site. Default is to pick the first available planting site.",
    )
    parser.add_argument(
        "--forest-type",
        "-f",
        choices=["Mangrove", "Terrestrial"],
        default="Terrestrial",
        help="The type of forest to record data for. Default is Terrestrial.",
    )
    parser.add_argument(
        "--type",
        "-t",
        choices=["biomass", "monitoring"],
        default="biomass",
        help="The type of ad-hoc observation to create. Default is biomass.",
    )
    add_terraware_args(parser)
    args = parser.parse_args()

    client = client_from_args(args)

    if args.site:
        planting_site_id = args.site
    else:
        organization_id = args.organization or client.get_default_organization_id(
            require_admin=False
        )
        sites = client.get(
            "/api/v1/tracking/sites?organizationId=" + str(organization_id)
        )["sites"]
        if not sites:
            raise Exception(f"Organization {organization_id} has no planting sites")
        planting_site_id = sites[0]["id"]
        print(f"Using planting site {planting_site_id}")

    # Get the organization's species list to use for species selection
    organization_id = client.get_planting_site(planting_site_id)["organizationId"]
    species_ids = [species["id"] for species in client.list_species(organization_id)]

    if not species_ids:
        raise Exception(f"Organization {organization_id} has no species defined")

    # Generate and complete the observation
    if args.type == "biomass":
        payload = generate_biomass_observation_payload(
            planting_site_id, args.forest_type, species_ids
        )
    else:
        payload = generate_monitoring_observation_payload(planting_site_id, species_ids)
    result = client.complete_ad_hoc_observation(payload)

    print(
        f"Created and completed {args.type} observation {result['observationId']} with plot {result['plotId']}"
    )


if __name__ == "__main__":
    main()
