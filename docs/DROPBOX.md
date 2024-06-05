# Configuring submission document storage on Dropbox

Sensitive documents submitted in response to deliverables are uploaded to Dropbox rather than to Google Drive. This requires configuring terraware-server with the right Dropbox API credentials.

These instructions can be used to set up a

## Prerequisites

- A Dropbox user with permission to read and write the folder you're going to use for documents. This can be your personal account or a dedicated one that's only used by terraware-server.

### Step 1: Create an app

Go to the [Dropbox app console](https://www.dropbox.com/developers/apps). Make sure you're log in as the user you're going to use.

Click "Create app".

Use these settings:

- API type: Scoped access.
- Access level: Full Dropbox. This is needed so that the server can upload files into regular Dropbox folders that are also used for other files.
- App name: Anything you want, but including "Terraware" in the name is probably a good idea for clarity.

Click "Create app."

### Step 2: Configure authentication

In the app's details, go to the Settings tab (you should already be there after creating the app).

Under OAuth 2, set "Allow public clients" to Disallow.

Optional: Copy the app key and app secret somewhere; you'll both later. Or you can come back to this page to get them when needed.

### Step 3: Configure permissions

In the app's settings, go to the Permissions tab. Enable the following:

- files.metadata.write
- files.content.write
- sharing.write

Click the "Submit" link.

### Step 4: Link the app to the account

This will require manually constructing an OAuth2 URL to get a code. The URL should look like:

        https://www.dropbox.com/oauth2/authorize?client_id=APP_KEY&response_type=code&token_access_type=offline

where `APP_KEY` is the app key from the "Settings" tab of the app's details (from step 2).

Go to this URL in a new browser tab/window. You'll be prompted to confirm that you really want to connect the app to your Dropbox account.

After granting permission to the app, you'll get a page with a temporary code, which will be used in the next step.

### Step 5: Generate a refresh token

This requires sending a POST request to Dropbox. Easiest is to do it from the command line:

        curl -d 'grant_type=authorization_code&client_id=APP_KEY&client_secret=APP_SECRET&code=CODE' https://api.dropbox.com/oauth2/token

Where `APP_KEY` and `APP_SECRET` are the app key and secret from the settings page (from step 2) and `CODE` is the code from the end of step 4.

You'll get back a JSON object. Look for `"refresh_token":` -- you will want the value in double quotes right after that.

### Step 6: Configure terraware-server

If you're using a local application YAML file, you can include the credentials in there. Only do this for local testing, and make sure you don't put them in a file that gets checked into version control!

```
terraware:
  dropbox:
    app-key: "APP_KEY"
    app-secret: "APP_SECRET"
    enabled: true
    refresh-token: "REFRESH_TOKEN_FROM_STEP_5"
```

For deployment or if you're running terraware-server with a Docker image, you'll want to put the values in environment variables:

- `TERRAWARE_DROPBOX_APPKEY`
- `TERRAWARE_DROPBOX_APPSECRET`
- `TERRAWARE_DROPBOX_ENABLED` (set this to `true`)
- `TERRAWARE_DROPBOX_REFRESHTOKEN`

