# Terraware-Server End-to-End Test Suite

This directory contains a test suite that exercises the server's API.

## Setup (for testing against locally-running server)

Install the dependencies in `requirements.txt`. You will probably want to do this in a venv:

```bash
python3 -m venv .venv
source .venv/bin/activate
pip3 install -r requirements.txt
```

Set the following environment variables. If you're running the test suite from PyCharm or IntelliJ, you can set these in a run configuration for the tests.

* `TEST_USERNAME` = The username (email address) to use to authenticate with the Keycloak instance the server is configured to use.
* `TEST_PASSWORD` = The password for `TEST_USERNAME`.
* `TEST_SPRING_PROFILES` = Comma-separated list of Spring profiles the server is using. These are used to look up some authentication information by loading application YAML files from the server source tree. If you've defined a local `dev` profile, you'll want to set this to `default,dev`.

You can also set the following if needed:

* `TEST_BASE_URL` = The server's base URL. Default is `http://localhost:8080`.

## Running the tests

With the server running, the venv activated, and the environment variables set, just run `pytest` in this directory.
