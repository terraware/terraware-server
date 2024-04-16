# Configuring support tickets with Atlassian

Feature requests and bug reports submitted are triaged as Jira service issues on Atlassian. This requires configuring terraware-server with the right Atlassian API credentials. 

## Prerequisites

- Atlassian Cloud site with Jira
- An Atlassian account with the target service management project permission

### Step 1: Generate an API Token and configure client authentication

Go to the [Atlassian API Token console](https://id.atlassian.com/manage-profile/security/api-tokens). API tokens can be managed here.

### Step 2: Find the Service Desk Key

The service desk tag is the project to hold support issues.

`https://{your-domain}.atlassian.net/jira/servicedesk/projects/{project-tag}/`

### Step 3: Add environmental variables

A local application yaml file:
```
terraware:
  atlassian:
    account: "ATLASSIAN ACCOUNT EMAIL"
    apiHostname = "{your-domain}.atlassian.net"
    apiToken = "API TOKEN (STEP 1)"
    enabled: true,
    serviceDeskKey = "SERVICE DESK ID (STEP 2)"
```

For docker: set the following env

- `TERRAWARE_ATLASSIAN_ACCOUNT`
- `TERRAWARE_ATLASSIAN_HOSTNAME`
- `TERRAWARE_ATLASSIAN_TOKEN`
- `TERRAWARE_ATLASSIAN_ENABLED` (set this to `true`)
- `TERRAWARE_ATLASSIAN_SERVICE_DESK_KEY`

