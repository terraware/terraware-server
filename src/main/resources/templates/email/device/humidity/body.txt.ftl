<#-- @ftlvariable name="" type="com.terraformation.backend.email.model.SensorBoundsAlert" -->
${strings("notification.seedBank.sensorBounds.humidity.email.body.1", device.name, value)}

${strings("notification.seedBank.sensorBounds.generic.email.body.2", facility.name)}

${strings("notification.seedBank.email.linkIntro")}
${facilityMonitoringUrl}


------------------------------

${strings("notification.email.text.footer")}
