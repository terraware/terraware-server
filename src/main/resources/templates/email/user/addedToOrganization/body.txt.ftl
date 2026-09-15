<#-- @ftlvariable name="" type="com.terraformation.backend.email.model.UserAddedToOrganization" -->
${strings("notification.user.addedToOrganization.email.body.1")}

${strings("notification.user.addedToOrganization.email.body.2", organization.name)}

<#if user.firstName??>${strings("notification.user.addedToOrganization.email.body.3", user.firstName)}<#else>${strings("notification.user.addedToOrganization.email.body.3.noname")}</#if>

${strings("notification.user.addedToOrganization.email.body.4", organization.name, admin.fullName, admin.email)}

${strings("notification.user.addedToOrganization.email.body.5")}

${strings("notification.user.addedToOrganization.email.buttonLabel")}
${organizationHomeUrl}

---

${strings("notification.user.addedToOrganization.email.body.6")}

* ${strings("notification.email.terrawareBenefits.1")}
* ${strings("notification.email.terrawareBenefits.2")}
* ${strings("notification.email.terrawareBenefits.3")}
* ${strings("notification.email.terrawareBenefits.4")}

${strings("notification.user.addedToOrganization.email.body.7")}
${learnMoreUrl}

------------------------------

${strings("notification.email.text.footer", manageSettingsUrl)}
