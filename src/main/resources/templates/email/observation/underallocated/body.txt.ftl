<#-- @ftlvariable name="" type="com.terraformation.backend.email.model.ObservationPlotsUnderallocated" -->
${strings("notification.observation.underallocated.email.body.1", plantingSiteName)}

<#list shortfalls as shortfall>
${strings("notification.observation.underallocated.email.body.2",
    shortfall.stratumName, shortfall.numConfigured, shortfall.numAllocated)}

</#list>

${strings("notification.observation.underallocated.email.body.3")}

${strings("notification.observation.underallocated.email.body.4")}

${observationUrl}


------------------------------

${strings("notification.email.text.footer", manageSettingsUrl)}
