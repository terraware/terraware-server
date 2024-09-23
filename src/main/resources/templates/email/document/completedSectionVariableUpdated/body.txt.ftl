<#-- @ftlvariable name="" type="com.terraformation.backend.email.model.CompletedSectionVariableUpdated" -->
${strings("notification.document.completedSectionVariableUpdated.email.title")}

${strings("notification.document.completedSectionVariableUpdated.email.body.1", documentName, sectionName, projectName)}

${strings("notification.document.completedSectionVariableUpdated.email.body.2")}

${documentUrl}

------------------------------

${strings("notification.email.text.footer", manageSettingsUrl)}
