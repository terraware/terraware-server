<#-- @ftlvariable name="" type="com.terraformation.backend.email.model.UnknownAutomationTriggered" -->
${strings("notification.seedBank.unknownAutomationTriggered.email.body.1", automation.name, facility.name)}
<#if message?has_content>

${strings("notification.seedBank.unknownAutomationTriggered.email.body.2", message)}
</#if>

${strings("notification.seedBank.email.linkIntro")}
${facilityMonitoringUrl}


------------------------------

${strings("notification.email.text.footer", manageSettingsUrl)}
