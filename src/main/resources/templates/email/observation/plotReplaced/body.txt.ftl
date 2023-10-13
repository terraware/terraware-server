<#-- @ftlvariable name="" type="com.terraformation.backend.email.model.ObservationPlotReplaced" -->
<#if !hasPrimaryContact>
${strings("notification.observation.monitoringPlotReplaced.email.body.1")}
</#if>

${strings("notification.observation.monitoringPlotReplaced.email.body.2", plantingSiteName)}

${strings("notification.observation.monitoringPlotReplaced.email.body.3", justification)}

${strings("notification.observation.monitoringPlotReplaced.email.body.4", duration.displayName)}


------------------------------

${strings("notification.email.text.footer", manageSettingsUrl)}
