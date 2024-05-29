package com.terraformation.backend.document.api

import com.terraformation.pdd.api.InternalEndpoint
import com.terraformation.pdd.api.SuccessResponsePayload
import com.terraformation.pdd.jooq.MethodologyId
import com.terraformation.pdd.jooq.tables.daos.MethodologiesDao
import com.terraformation.pdd.jooq.tables.pojos.MethodologiesRow
import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@InternalEndpoint
@RequestMapping("/api/v1/methodologies")
@RestController
class MethodologiesController(
    val methodologiesDao: MethodologiesDao,
) {
  @GetMapping
  @Operation(summary = "Gets a list of all the valid methodologies.")
  fun listMethodologies(): ListMethodologiesResponsePayload {
    val rows = methodologiesDao.findAll()

    return ListMethodologiesResponsePayload(rows.map { MethodologyPayload(it) })
  }
}

data class MethodologyPayload(
    val id: MethodologyId,
    val name: String,
) {
  constructor(row: MethodologiesRow) : this(id = row.id!!, name = row.name!!)
}

data class ListMethodologiesResponsePayload(val methodologies: List<MethodologyPayload>) :
    SuccessResponsePayload
