#!/usr/bin/env python3
import argparse
import requests


def main():
    parser = argparse.ArgumentParser("Query or advance the server's clock.")
    parser.add_argument(
        "--days", "-d", type=int, help="Number of days to advance clock."
    )
    parser.add_argument(
        "--server",
        "-s",
        default="http://localhost:8080",
        help="Base URL of terraware-server.",
    )
    args = parser.parse_args()

    if args.days:
        r = requests.post(
            f"{args.server}/api/v1/seedbank/clock/advance", json={"days": args.days}
        )
        r.raise_for_status()

    r = requests.get(f"{args.server}/api/v1/seedbank/clock")
    print("Server time is " + r.json()["currentTime"])


if __name__ == "__main__":
    main()
