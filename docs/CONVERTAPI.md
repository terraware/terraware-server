# ConvertAPI for additional image format support

We use [ConvertAPI](https://convertapi.com/) to support generating thumbnail images when the original image file is in a format the server can't read natively, e.g., HEIC.

Setting up a local dev environment to interact with ConvertAPI requires some setup steps.

**Note:** You only need to do this if you want to work with images of unsupported formats. If you do all your local testing with JPEG or PNG images, you don't need ConvertAPI.

## Step 1: Log into ConvertAPI

Either get an invitation to an existing ConvertAPI team or sign up for an account on your own. At the time of this writing, they had a free trial period that may suffice for your needs.

Once you've logged in, you should see a dashboard with an overview of conversion activity.

## Step 2: Create an API token

While it's possible to share API tokens across environments, it's preferable to use a separate token for each developer.

Click the "Authentication" item in the left navbar. You should see a list of the existing API tokens.

Click the "Create New Token" button and, depending on your needs, choose an expiration date and/or a usage limit. The name is up to you but `<your username> dev` is a reasonable pattern.

The new token should appear in the token list.

## Step 3: Configure terraware-server

You can tell Terraware about the API token via environment variables or by putting it in `src/main/resources/application-dev.yaml`:

```yaml
terraware:
  convert-api:
    enabled: true
    api-key: "Your API token from step 2"
```

If you're running terraware-server in a Docker container, you can use environment variables, e.g., `TERRAWARE_CONVERTAPI_ENABLED=true`.

## Step 4: Try it out

You'll need an HEIC image file, e.g., a photo from an iPhone.

Launch terraware-server and terraware-web.

Log into [the ConvertAPI test page in the admin UI](https://localhost:3000/admin/convertApi) as a super-admin user. That page is also accessible from the admin UI main menu.

Use the "Upload File and View Thumbnail" form to upload your HEIC file.

You should see a file ID in the success message, and then, after a brief delay, you should see a thumbnail version of the file you uploaded.
