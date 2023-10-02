<#-- @ftlvariable name="" type="com.terraformation.backend.email.model.ObservationRescheduled" -->
${strings("notification.observation.rescheduled.email.body.1", plantingSiteName)}

${strings("notification.observation.rescheduled.email.body.2", originalStartDateString, originalEndDateString)}

${strings("notification.observation.rescheduled.email.body.3", newStartDateString, newEndDateString)}

------------------------------

${strings("notification.email.text.footer", manageSettingsUrl)}
