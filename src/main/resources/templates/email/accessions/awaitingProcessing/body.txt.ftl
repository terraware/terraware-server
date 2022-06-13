<#-- @ftlvariable name="" type="com.terraformation.backend.email.model.AccessionsAwaitingProcessing" -->
<#if numAccessions == 1>
  ${organizationName} has 1 accession that has been waiting since drop off for at least 1 week and is ready to be processed. Please check it in for processing now.
<#else>
  ${organizationName} has ${numAccessions} accessions that have been waiting since drop off for at least 1 week and are ready to be processed. Please check them in for processing now.
</#if>

Please click the link below to go to your Terraware account where you can view the accessions.
${accessionsUrl}


------------------------------

Terraformation Inc.
PO Box 3470, PMB 15777, Honolulu, HI 96801-3470

https://twitter.com/TF_Global
https://www.linkedin.com/company/terraformation/
https://www.instagram.com/globalterraform/
https://www.facebook.com/GlobalTerraform
https://terraformation.com/
