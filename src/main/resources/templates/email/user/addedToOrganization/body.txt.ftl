<#-- @ftlvariable name="" type="com.terraformation.backend.email.model.UserAddedToOrganization" -->
${strings("notification.user.addedToOrganization.email.body.1", organization.name)}

${strings("notification.user.addedToOrganization.email.body.2", admin.fullName, admin.email)}

${strings("notification.organization.email.linkIntro")}
${organizationHomeUrl}


------------------------------

${strings("notification.email.text.footer", manageSettingsUrl)}
