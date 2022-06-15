<#-- @ftlvariable name="" type="com.terraformation.backend.email.model.UnknownAutomationTriggered" -->
Automation ${automation.name} has been triggered at ${facility.name}.
<#if message?has_content>

Additional details: ${message}
</#if>

Please click the link below to go to your Terraware account where you can view the seed bank.
${facilityMonitoringUrl}


------------------------------

Terraformation Inc.
PO Box 3470, PMB 15777, Honolulu, HI 96801-3470

https://twitter.com/TF_Global
https://www.linkedin.com/company/terraformation/
https://www.instagram.com/globalterraform/
https://www.facebook.com/GlobalTerraform
https://terraformation.com/
