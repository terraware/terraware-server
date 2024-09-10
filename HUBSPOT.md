# Configuring HubSpot integration in dev envrionments

When accelerator applications are submitted, terraware-server can create a deal and associated entities in HubSpot to track communication with the applicant.

HubSpot offers a sandbox that can be used to test the integration without affecting any production data. This document describes how to set it up and test it.

## Setup

### Create a HubSpot developer account

[The HubSpot API overview page](https://developers.hubspot.com/docs/api/overview) includes a link to create a developer account; click that link. Don't click the "private app" link; that's not the setup we use.

Go through the signup and developer account creation flow. It's fine to use Google login here, even if you already use your Google account to log into the regular HubSpot site.

You'll be prompted for a company name. This can be anything you want; the UI says it has to be "unique" but it just means "unique among your personal developer accounts."

### Create an app

Once you're through the account creation process, you'll end up on the developer home page. (Exact URL will vary since it includes an account ID.)

Click the "Create app" button. On the next page, click "Create app" again.

Give your app a name, e.g., "Terraware".

Switch to the "Auth" tab and scroll down to the "Redirect URLs" section.

Enter this URL, assuming your local Terraware instance is running on the default terraware-web proxy port of 3000:

        http://localhost:3000/admin/hubSpotCallback

Add the following required scopes, in addition to the default `oauth` one:

* `crm.objects.companies.read`
* `crm.objects.companies.write`
* `crm.objects.contacts.read`
* `crm.objects.contacts.write`
* `crm.objects.deals.read`
* `crm.objects.deals.write`

Click "Create app".

### Create a test account

You'll need a "test account" in addition to your developer account. The test account acts like a regular HubSpot account and can have deals, companies, and so forth; you can log into it with the regular HubSpot UI. The developer account, on the other hand, is just for managing apps and API access.

Click the "Home" icon in the HubSpot left nav to go back to the developer home page.

Click "Create test account".

Click "Create developer test account".

Give it a name; doesn't matter what, but pick something you won't confuse with the real HubSpot account.

### Get the app's credentials

Click the "Apps" icon (the cube) in the HubSpot left nav.

Click the link for your app.

Switch to the "Auth" tab.

Hit the "Show" link under the client secret. You will need the client ID and client secret, but not the app ID.

### Configure terraware-server

Add the client ID and secret to your terraware-server configuration, either by editing your local `application-dev.yaml` like:

```yaml
terraware:
  hubspot:
    enabled: true
    client-id: "YOUR APP'S CLIENT ID"
    client-secret: "YOUR APP'S CLIENT SECRET"
```

or via environment variables, if you're running terraware-server using a Docker image:

* `TERRAWARE_HUBSPOT_ENABLED` (set this to `true`)
* `TERRAWARE_HUBSPOT_CLIENTID`
* `TERRAWARE_HUBSPOT_CLIENTSECRET`

### Configure the test account

The HubSpot integration expects the HubSpot account to have some customizations in place; you'll need to configure them using HubSpot's web UI.

In the HubSpot console's top nav, click the account dropdown and then click on the account ID. You should see a menu of accounts including your newly-created test account. Click it.

You'll be taken to the regular HubSpot home page, logged in as the test account.

#### Create a pipeline

Click the settings button in the top nav (gear icon).

Under "Data Management" in the settings menu, expand the "Objects" menu and click "Deals".

Switch to the "Pipelines" tab and open the pipelines menu. Select "Create pipeline".

Enter a pipeline name of `Accelerator Projects` and hit the "Create pipeline" button.

Change the name of the first stage to `Application` and hit the "Save" button.

#### Create custom properties

Under "Data Management" in the settings menu, click "Properties".

##### Deal: Application Reforestable Land

In the "Select an object" menu, choose "Deal properties".

Click "Create property".

Enter a label of `Application Reforestable Land`.

Change the internal name to `project_hectares`.

Set the group to anything you like.

On the "Field type" page (left nav), select "Number".

Click "Create".

##### Deal: Project Country

Click "Create property".

Enter a label of `Project Country`. (The default internal name is fine this time.)

Set the group to anything you like.

On the "Field type" page, select "Multiple checkboxes".

Enter whichever country names you're going to be using for your test applications. These should be the full English names, the same as the `name` column in the Terraware database's `countries` table. For example, `United States` or `Kenya` or `Brazil`.

_Make sure the internal name is the same as the label!_ By default, HubSpot will give you internal names with underscores and lower-case letters, but you want the internal names to be identical to the labels with spaces and mixed case.

Note that you can add more countries later, so it's fine to start with just a few.

When you're done adding countries, click "Create".

##### Contact: Full Name

In the "Select an object" menu, choose "Contact properties".

Click "Create property".

Enter a label of `Full Name`.

Set the group to anything you like.

On the "Field type" page, select "Single-line text".

Click "Create".

### Connect terraware-server to your app

Start terraware-server and log in with a super-admin account.

In the admin UI, click the management link under the "HubSpot integration" section.

There will be a message saying no HubSpot credentials are configured.

Click the "Authorize" button, which will redirect you to HubSpot.

If HubSpot shows you a list of accounts, choose the _test account_ (not your developer account).

Click "Connect app".

HubSpot should redirect you back to the admin UI, which should show a message saying the integration is configured.

## Testing

The admin UI has some controls you can use to test the HubSpot integration. They should be fairly self-explanatory.

You'll need to create a deal before you can create a contact or a company since the integration associates deals with contacts and companies. The deal ID will be shown in the success message and you'll need to copy-paste it into the text fields.

### Resetting the credentials

The "Reset" button on the HubSpot integration page in the admin UI will delete the HubSpot credentials (refresh token) from the Terraware database.

You will normally not want to do this unless you're testing the credentials setup process or you want to switch to a different HubSpot account.
