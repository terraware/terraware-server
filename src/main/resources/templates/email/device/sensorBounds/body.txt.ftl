<#-- @ftlvariable name="" type="com.terraformation.backend.email.model.SensorBoundsAlert" -->
${strings("notification.seedBank.sensorBounds.generic.email.body.1", automation.timeseriesName, device.name, value)}

${strings("notification.seedBank.sensorBounds.generic.email.body.2", facility.name)}

${strings("notification.seedBank.email.linkIntro")}
${facilityMonitoringUrl}


------------------------------

${strings("notification.email.text.footer", manageSettingsUrl)}
