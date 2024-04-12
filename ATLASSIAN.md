# Configuring support tickets with Atlassian

Feature requests and bug reports submitted are triaged as Jira service issues on Atlassian. This requires configuring terraware-server with the right Atlassian API credentials. 

## Prerequisites

- Atlassian Cloud site with Jira
- An Atlassian account with the target service management project permission

### Step 1: Generate an API Token and configure client authentication

Go to the [Atlassian API Token console](https://id.atlassian.com/manage-profile/security/api-tokens). API tokens can be managed here.

### Step 2: Configure Basic Auth Header 

This step describes how to use the generated API token to invoke Jira APIs, which is required to retrieve some additional values for configs

Details can be found on [Basic auth for REST APIs](https://developer.atlassian.com/cloud/jira/platform/basic-auth-for-rest-apis/).

```bash
curl -D- \
   -u {atlassian_email}:{api_token} \
   -X GET \
   -H "Content-Type: application/json" \
   https://{your-domain}.atlassian.net/rest/...
```

### Step 3: Find the Service Desk ID

The only way to find a service desk ID is via Jira REST API.

```bash
curl -D- \
   -u {atlassian_email}:{api_token} \
   -X GET \
   -H "Content-Type: application/json" \
   https://{your-domain}.atlassian.net/rest/servicedeskapi/servicedesk/
```

Take note of the `id` for the desired project

### Step 4: Find the Bug Report Request ID and the Feature Request ID

```bash
curl -D- \
   -u {atlassian_email}:{api_token} \
   -X GET \
   -H "Content-Type: application/json" \
https://{your-domain}.atlassian.net/rest/servicedeskapi/servicedesk/{serviceDeskId}/requesttype/
```

Take note of the `id` for "Suggest a new Feature", and "Report a Bug".

### Step 5: Add environmental variables

A local application yaml file:
```
terraware:
  atlassian:
    account: "ATLASSIAN ACCOUNT EMAIL"
    apiHostname = "{your-domain}.atlassian.net"
    apiToken = "API TOKEN (STEP 2)"
    bugReportTypeId = "BIG REPORT TYPE ID (STEP 4)"
    enabled: true,
    serviceDeskId = "SERVICE DESK ID (STEP 3)"
    featureRequestTypeID = "FEATURE REQUEST TYPE ID (STEP 4)"
```

For docker: set the following env

- `TERRAWARE_ATLASSIAN_ACCOUNT`
- `TERRAWARE_ATLASSIAN_HOSTNAME`
- `TERRAWARE_ATLASSIAN_TOKEN`
- `TERRAWARE_ATLASSIAN_BUG_REPORT_TYPE_ID`
- `TERRAWARE_ATLASSIAN_ENABLED` (set this to `true`)
- `TERRAWARE_ATLASSIAN_SERVICE_DESK_ID`
- `TERRAWARE_ATLASSIAN_FEATURE_REQUEST_TYPE_ID`

