# Mux for video streaming

We use [Mux](https://mux.com/) to host streamable versions of user-uploaded videos.

Setting up a local dev environment to interact with Mux requires some setup steps.

**Note:** You only need to do this if you want to test or work on video-related functionality.

## Ngrok

Mux sends webhook requests when videos are finished processing or when it runs into errors processing a video. It also needs to be able to fetch the original video files from terraware-server. Receiving requests from external services in a firewalled local dev environment can be a challenge.

The recommended option is to use [Ngrok](https://ngrok.com/).

Sign up for a free account on Ngrok's website.

Install the `ngrok` command (e.g., using Homebrew if you're using MacOS) and configure it with an auth token. The Ngrok website should give you detailed installation and configuration instructions once you've signed up.

Then you can run `ngrok` to forward requests to terraware-server. You can either point it directly at terraware-server's HTTP port (8080 by default) or at the terraware-web dev service port (3000 by default). You may want to run it in a new window.

        ngrok http http://localhost:8080

It will give you a bunch of status information and a request log. The important piece of status is the public URL, which you'll find on the "Forwarding" line. You'll need that later.

## Mux

### Step 1: Log into Mux

Either get an invitation to an existing Mux organization or sign up for a Mux account on your own. For development purposes, you can use their free tier.

Once you've logged in, you should see the Mux dashboard, either for your own personal organization or the one you were invited to.

### Step 2: Create an environment

A Mux "environment" is like a child account. Each environment has its own API keys, and its content is tracked separately from the content of other environments. You'll want an environment for your local development.

In the dashboard left nav, click the "Environments" item.

Click "Create environment" in the main part of the page.

Give the environment a name, e.g., your name followed by "local dev." Choose "Development" as the deployment type.

Click the "Create" button.

### Step 3: Create an access token

Make sure the environment you've just created is selected; its name should appear in the top navbar.

In the left nav, go to "Settings." If you're not taken to the "Access Tokens" settings page by default, go there.

Click "Create token."

Give the token a name (which is just for display purposes; pick whatever you want) and give it write permissions for Mux Video.

Copy both the token ID and the secret key somewhere, or download them as a `.env` file using the link provided.

### Step 4: Create a webhook

Go to the "Webhooks" settings page.

Click "Create webhook."

Construct an HTTPS URL using the hostname from the `ngrok` command and the path `/api/v1/webhooks/mux`. It'll look something like `https://919191919191.ngrok-free.app/api/v1/webhooks/mux`.

Save the webhook. You should see it in the list of webhooks.

Copy the value of the "Secret" column somewhere; you'll need it later.

### Step 5: Create a signing key

Go to the "Signing Keys" settings page.

Click "Generate Key."

Save the key ID and secret somewhere; you'll need them later.

## Configure terraware-server

Now you'll need to tell terraware-server about all of the above. You can put them in `src/main/resources/application-dev.yaml`. It'll look like this:

```yaml
terraware:
  mux:
    enabled: true
    # Set this to your Ngrok forwarding URL (no URL path, just scheme and host).
    external-url: "https://11111111111.ngrok-free.app"
    signing-key-id: "Your signing key ID from step 5"
    signing-key-private: "Your signing key secret from step 5; this will be a pretty long value"
    token-id: "Your token ID from step 3"
    token-secret: "Your token secret from step 3"
    webhook-secret: "Your webhook secret from step 4"
```

Or, if you're running terraware-server using a Docker image, you can set all these values in environment variables, e.g., `TERRAWARE_MUX_ENABLED=true`.

## Changing the behavior of the integration

There are some config options you can use to change how the Mux integration behaves. As usual, these can be set in either `application-dev.yaml` or environment variables.

### Disabling test assets

If you want to test longer videos, you'll need to disable the use of test assets.

```yaml
terraware:
  mux:
    use-test-assets: false
```

or

`TERRAWARE_MUX_USETESTASSETS=false`

### Adjusting stream expiration time

To prevent users from sharing video stream URLs and bypassing our access controls, we generate time-limited signed URLs. By default, the URLs are good for 1 hour. For testing handling of URL expiration, it can be useful to make them expire more quickly.

```yaml
terraware:
  mux:
    stream-expiration-seconds: 30
```

or

`TERRAWARE_MUX_STREAMEXPIRATIONSECONDS=30`
