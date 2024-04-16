package com.terraformation.backend.support.atlassian.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class ServiceDeskModel(
    val id: Int,
    val projectName: String,
    val projectKey: String,
)
