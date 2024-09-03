<#-- @ftlvariable name="" type="com.terraformation.backend.email.model.ApplicationSubmitted" -->
${strings("notification.application.submitted.email.body", organizationName, submissionDate)}
${strings("notification.application.submitted.email.buttonLabel")}
${applicationUrl}
------------------------------
${strings("notification.email.text.footer", manageSettingsUrl)}