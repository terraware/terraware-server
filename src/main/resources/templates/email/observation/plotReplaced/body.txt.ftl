<#-- @ftlvariable name="" type="com.terraformation.backend.email.model.ObservationPlotReplaced" -->
${strings("notification.observation.monitoringPlotReplaced.email.body.1", organizationName)}

<#if !hasPrimaryContact>
${strings("notification.observation.monitoringPlotReplaced.email.body.2")}

</#if>
${strings("notification.observation.monitoringPlotReplaced.email.body.3", plantingSiteName)}

${strings("notification.observation.monitoringPlotReplaced.email.body.4", justification)}

${strings("notification.observation.monitoringPlotReplaced.email.body.5", duration.displayName)}


------------------------------

${strings("notification.email.text.footer", manageSettingsUrl)}
