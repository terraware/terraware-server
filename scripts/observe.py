#!/usr/bin/env python3
import argparse
from datetime import datetime, timezone
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


def generate_complete_plot_payload(plot_id, species_ids):
    num_plants = random.randint(25, 200)
    return {
        "conditions": [],
        "notes": f"Notes for plot {plot_id}",
        "observedTime": isoformat(int(time.time())),
        "plants": [generate_recorded_plant(species_ids) for _ in range(0, num_plants)],
    }


def get_incomplete_plot_ids(client, observation_id):
    my_user_id = client.get_me()["id"]
    return [
        plot["plotId"]
        for plot in client.list_observation_plots(observation_id)
        if "completedByUserId" not in plot
        and ("claimedByUserId" not in plot or plot["claimedByUserId"] == my_user_id)
    ]


def main():
    parser = argparse.ArgumentParser("Complete observations of monitoring plots")
    parser.add_argument(
        "--observation", "-o", type=int, help="Record results for this observation."
    )
    parser.add_argument(
        "--organization",
        "-O",
        type=int,
        help="Choose observation from this organization. Default is the current user's "
        + "lowest-numbered organization.",
    )
    parser.add_argument(
        "--plot",
        "-p",
        type=int,
        help="Record results for this plot. Default is to record results for multiple plots.",
    )
    parser.add_argument(
        "--num-plots",
        "-n",
        type=int,
        help="Record results for this many plots. Default is to record results for all "
        + "incomplete plots. Ignored if --plot is specified.",
    )
    add_terraware_args(parser)
    args = parser.parse_args()

    client = client_from_args(args)

    if args.observation:
        observation_id = args.observation
        observation = client.get_observation(observation_id)
        planting_site = client.get_planting_site(observation["plantingSiteId"])
        organization_id = planting_site["organizationId"]
    else:
        organization_id = client.get_default_organization_id(require_admin=False)
        incomplete_observations = [
            observation
            for observation in client.list_observations(organization_id)
            if observation["state"] in ["InProgress", "Overdue"]
        ]
        if not incomplete_observations:
            raise Exception(
                f"Organization {organization_id} has no incomplete observations"
            )
        observation_id = incomplete_observations[0]["id"]
        print(f"Picked observation {observation_id}")

    if args.plot:
        plot_ids = [args.plot]
    else:
        plot_ids = get_incomplete_plot_ids(client, observation_id)
        if args.num_plots:
            plot_ids = plot_ids[: args.num_plots]

    if not plot_ids:
        raise Exception("No incomplete monitoring plots found in observation.")

    # Use the organization's species list, rather than the list of planted species in each subzone,
    # so we can run this without needing to first create a bunch of nursery withdrawals.
    species_ids = [species["id"] for species in client.list_species(organization_id)]

    for plot_id in plot_ids:
        client.claim_observation_plot(observation_id, plot_id)
        print(f"Completing plot {plot_id}")
        payload = generate_complete_plot_payload(plot_id, species_ids)
        client.complete_observation(observation_id, plot_id, payload)


if __name__ == "__main__":
    main()
