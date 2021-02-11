# Utility and test scripts for seedbank-server

## Prerequisites

Python 3.7 or higher.

## Installation

Make a venv and install the dependencies:

```
python3 -m venv .venv
source .venv/bin/activate
pip3 install -r requirements.txt
```

## Creating test accessions

To create 1000 test accessions using a server running on port 8080 on the local host:

```
./create_accessions.py -n 1000
```

If the server is running somewhere else, give its base URL as an argument:

```
./create_accessions.py -n 1000 http://somewhere:12345
```

To see the server's response with additional fields populated, use the `-v` option:

```
./create_accessions.py -n 1 -v
```
