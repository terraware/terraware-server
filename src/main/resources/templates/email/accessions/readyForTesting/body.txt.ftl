<#-- @ftlvariable name="" type="com.terraformation.backend.email.model.AccessionsReadyForTesting" -->
<#if numAccessions == 1>
  ${organizationName} has 1 accession that has finished processing for at least ${weeks} weeks and ready to be tested for %RH.
<#else>
  ${organizationName} has ${numAccessions} accessions that have finished processing for at least ${weeks} weeks and ready to be tested for %RH.
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
