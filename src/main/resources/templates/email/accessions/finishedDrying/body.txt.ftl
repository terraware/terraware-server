<#-- @ftlvariable name="" type="com.terraformation.backend.email.model.AccessionsFinishedDrying" -->
<#if numAccessions == 1>
  ${organizationName} has 1 accession that is past it's drying date and is ready to be stored. Please move it to storage now.
<#else>
  ${organizationName} has ${numAccessions} accessions that are past their drying date and are ready to be stored. Please move them to storage now.
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
