package com.terraformation.backend.accelerator

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.db.accelerator.tables.references.HUBSPOT_TOKEN
import com.terraformation.backend.db.default_schema.tables.daos.CountriesDao
import com.terraformation.backend.log.perClassLogger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import jakarta.inject.Named
import jakarta.ws.rs.core.UriBuilder
import java.math.BigDecimal
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.InstantSource
import kotlinx.coroutines.runBlocking
import org.jooq.DSLContext
import org.springframework.security.access.AccessDeniedException

@Named
class HubSpotService(
    private val clock: InstantSource,
    private val config: TerrawareServerConfig,
    private val countriesDao: CountriesDao,
    private val dslContext: DSLContext,
    private val httpClient: HttpClient,
) {
  companion object {
    private const val ACCELERATOR_PIPELINE_LABEL = "Accelerator Projects"
    private const val APPLICATION_STAGE_LABEL = "Application"

    // HubSpot-defined properties
    private const val COMPANY_PROPERTY_NAME = "name"
    private const val COMPANY_PROPERTY_WEBSITE = "website"
    private const val CONTACT_PROPERTY_EMAIL = "email"
    private const val CONTACT_PROPERTY_FIRST_NAME = "firstname"
    private const val DEAL_PROPERTY_NAME = "dealname"
    private const val DEAL_PROPERTY_PIPELINE = "pipeline"
    private const val DEAL_PROPERTY_STAGE = "dealstage"

    // Custom properties
    private const val CONTACT_PROPERTY_FULL_NAME = "full_name"
    private const val DEAL_PROPERTY_APPLICATION_REFORESTABLE_LAND = "project_hectares"
    private const val DEAL_PROPERTY_PROJECT_COUNTRY = "project_country"

    // From https://developers.hubspot.com/docs/api/crm/associations#association-type-id-values
    private const val ASSOCIATION_CATEGORY = "HUBSPOT_DEFINED"
    private const val ASSOCIATION_TYPE_CONTACT_TO_DEAL = 4
    private const val ASSOCIATION_TYPE_COMPANY_TO_DEAL = 342

    private val hubSpotBaseUrl = URI("https://api.hubapi.com/")

    private val log = perClassLogger()
  }

  private var accessToken: String? = null
  private var accessTokenExpiration = Instant.EPOCH
  private var acceleratorPipelineId: String? = null
  private var applicationStageId: String? = null
  private val dealPageUrlPrefix: URI by lazy { initDealPageUrlPrefix() }

  private val redirectUri = config.webAppUrl.resolve("/admin/hubSpotCallback").toString()

  private val projectCountryOptions: Set<String> by lazy {
    sendRequest<GetProjectCountryPropertyResponse>(
            "/crm/v3/properties/deals/$DEAL_PROPERTY_PROJECT_COUNTRY"
        )
        .options
        .map { it.value }
        .toSet()
  }

  /**
   * Creates a deal and associates a contact and a company with it.
   *
   * @return The URL of the deal's page in the HubSpot UI.
   */
  fun createApplicationObjects(
      applicationReforestableLand: BigDecimal?,
      companyName: String,
      contactEmail: String?,
      contactName: String?,
      countryCode: String?,
      dealName: String,
      website: String?,
  ): URI {
    val countryName = countryCode?.let { countriesDao.fetchOneByCode(it)?.name }

    val dealId = createDeal(dealName, countryName, applicationReforestableLand)
    val contactId = contactEmail?.let { createContact(contactEmail, contactName, dealId) }
    val companyId = website?.let { createCompany(companyName, website, dealId) }

    log.info("Created deal $dealId, contact $contactId, company $companyId for $dealName")

    return getDealPageUrl(dealId)
  }

  /**
   * Creates a new deal and populates its properties.
   *
   * @return The deal's unique ID.
   */
  fun createDeal(
      dealName: String,
      countryName: String?,
      applicationReforestableLand: BigDecimal?,
  ): String {
    val hubSpotCountryName =
        if (countryName != null && countryName in projectCountryOptions) {
          countryName
        } else {
          null
        }

    val request =
        CreateDealRequest(
            CreateDealRequest.Properties(
                applicationReforestableLand = applicationReforestableLand,
                countryName = hubSpotCountryName,
                dealName = dealName,
                pipeline = getAcceleratorPipelineId(),
                stage = getApplicationStageId(),
            )
        )

    val response: CreateDealResponse = sendRequest("/crm/v3/objects/deals", request)

    return response.id
  }

  /**
   * Creates a new contact and associates it with a deal.
   *
   * @return The contact's unique ID.
   */
  fun createContact(email: String, name: String?, dealId: String): String {
    return try {
      val escapedEmail = URLEncoder.encode(email, StandardCharsets.UTF_8)
      val existingContactResponse: ResponseWithId =
          sendRequest("/crm/v3/objects/contacts/$escapedEmail?idProperty=email")
      val existingContactId = existingContactResponse.id

      sendRequest<Any>(
          "/crm/v3/objects/contacts/$existingContactId/associations/deals/$dealId/$ASSOCIATION_TYPE_CONTACT_TO_DEAL",
          method = HttpMethod.Put,
      )

      existingContactId
    } catch (e: ClientRequestException) {
      if (e.response.status == HttpStatusCode.NotFound) {
        val request = CreateContactRequest(email, name, dealId)
        val response: ResponseWithId = sendRequest("/crm/v3/objects/contacts", request)
        response.id
      } else {
        throw e
      }
    }
  }

  /**
   * Creates a new company and associates it with a deal.
   *
   * @return The company's unique ID.
   */
  fun createCompany(name: String, website: String, dealId: String): String {
    val searchRequest = SearchObjectsRequest(COMPANY_PROPERTY_WEBSITE, "EQ", website)
    val searchResponse: SearchObjectsResponse =
        sendRequest("/crm/v3/objects/companies/search", searchRequest)

    return if (searchResponse.results.isNotEmpty()) {
      val existingCompanyId = searchResponse.results.first().id

      sendRequest<Any>(
          "/crm/v3/objects/companies/$existingCompanyId/associations/deals/$dealId/$ASSOCIATION_TYPE_COMPANY_TO_DEAL",
          method = HttpMethod.Put,
      )

      existingCompanyId
    } else {
      val createRequest = CreateCompanyRequest(name, website, dealId)
      val createResponse: ResponseWithId = sendRequest("/crm/v3/objects/companies", createRequest)

      createResponse.id
    }
  }

  /**
   * Returns true if this server has gone through the HubSpot authorization flow and has a refresh
   * token.
   */
  fun isAuthorized(): Boolean {
    return dslContext.fetchExists(HUBSPOT_TOKEN)
  }

  /** Removes the HubSpot refresh token from the database. */
  fun clearCredentials() {
    dslContext.deleteFrom(HUBSPOT_TOKEN).execute()
  }

  /** Returns a URL to get the user's authorization to connect this server to HubSpot. */
  fun getAuthorizationUrl(): URI {
    ensureEnabled()

    val scopes =
        listOf(
            "crm.objects.companies.read",
            "crm.objects.companies.write",
            "crm.objects.contacts.read",
            "crm.objects.contacts.write",
            "crm.objects.deals.read",
            "crm.objects.deals.write",
            "oauth",
        )

    return UriBuilder.fromUri("https://app.hubspot.com/oauth/authorize")
        .queryParam("client_id", config.hubSpot.clientId)
        .queryParam("scope", scopes.joinToString(" "))
        .queryParam("redirect_uri", redirectUri)
        .build()
  }

  /**
   * Requests a refresh token from HubSpot and stores it in the database. This is called at the end
   * of the authorization flow.
   *
   * @param code The authorization code passed to the server by HubSpot when it redirects the user
   *   back to the admin UI at the end of the authorization flow.
   */
  fun populateRefreshToken(code: String) {
    ensureEnabled()

    runBlocking {
      val response =
          httpClient.submitForm(
              "https://api.hubapi.com/oauth/v1/token",
              formParameters =
                  parameters {
                    append("grant_type", "authorization_code")
                    append("client_id", config.hubSpot.clientId!!)
                    append("client_secret", config.hubSpot.clientSecret!!)
                    append("redirect_uri", redirectUri)
                    append("code", code)
                  },
          )

      if (response.status == HttpStatusCode.OK) {
        val payload: TokenResponse = response.body()

        val rowsUpdated =
            dslContext
                .update(HUBSPOT_TOKEN)
                .set(HUBSPOT_TOKEN.REFRESH_TOKEN, payload.refreshToken)
                .execute()
        if (rowsUpdated == 0) {
          dslContext
              .insertInto(HUBSPOT_TOKEN)
              .set(HUBSPOT_TOKEN.REFRESH_TOKEN, payload.refreshToken)
              .execute()
        }

        accessToken = payload.accessToken
        accessTokenExpiration = clock.instant().plusSeconds(payload.expiresIn)
      } else {
        throw AccessDeniedException(
            "Unable to request refresh token from HubSpot (HTTP ${response.status.value})"
        )
      }
    }
  }

  /** Returns the unique ID of the deal pipeline that's used for accelerator projects. */
  fun getAcceleratorPipelineId(): String {
    return acceleratorPipelineId
        ?: run {
          val pipelinesResponse: ListPipelinesResponse = sendRequest("/crm/v3/pipelines/deals")

          val pipeline =
              pipelinesResponse.results.firstOrNull { it.label == ACCELERATOR_PIPELINE_LABEL }
                  ?: throw IllegalStateException("Pipeline not found: $ACCELERATOR_PIPELINE_LABEL")

          applicationStageId =
              pipeline.stages.firstOrNull { it.label == APPLICATION_STAGE_LABEL }?.id
                  ?: throw IllegalStateException(
                      "Pipeline stage not found: $APPLICATION_STAGE_LABEL"
                  )
          acceleratorPipelineId = pipeline.id

          pipeline.id
        }
  }

  /** Returns the unique ID of the deal stage that's used for new accelerator applications. */
  fun getApplicationStageId(): String {
    getAcceleratorPipelineId()

    return applicationStageId
        ?: throw IllegalStateException("Application state not set after fetching pipeline info")
  }

  private fun fetchRefreshToken(): String? {
    return dslContext
        .select(HUBSPOT_TOKEN.REFRESH_TOKEN)
        .from(HUBSPOT_TOKEN)
        .fetchOne(HUBSPOT_TOKEN.REFRESH_TOKEN)
  }

  private fun getAccessToken(): String {
    if (accessTokenExpiration.isBefore(clock.instant())) {
      accessToken = null
    }

    return accessToken
        ?: runBlocking {
          val refreshToken =
              fetchRefreshToken()
                  ?: throw IllegalStateException("No HubSpot credentials appear to be configured")

          log.debug("Requesting new access token")

          val response =
              httpClient.submitForm(
                  "https://api.hubapi.com/oauth/v1/token",
                  formParameters =
                      parameters {
                        append("grant_type", "refresh_token")
                        append("client_id", config.hubSpot.clientId!!)
                        append("client_secret", config.hubSpot.clientSecret!!)
                        append("redirect_uri", redirectUri)
                        append("refresh_token", refreshToken)
                      },
              )

          if (response.status == HttpStatusCode.OK) {
            val payload: TokenResponse = response.body()

            accessToken = payload.accessToken
            accessTokenExpiration = clock.instant().plusSeconds(payload.expiresIn)

            payload.accessToken
          } else {
            throw AccessDeniedException(
                "Unable to request access token from HubSpot (HTTP ${response.status.value})"
            )
          }
        }
  }

  private fun initDealPageUrlPrefix(): URI {
    val response: AccountDetailsResponse = sendRequest("/account-info/v3/details")

    return URI.create("https://${response.uiDomain}/contacts/${response.portalId}/deal/")
  }

  private fun getDealPageUrl(dealId: String): URI {
    return dealPageUrlPrefix.resolve(dealId)
  }

  private suspend fun doSendRequest(
      path: String,
      body: Any? = null,
      httpMethod: HttpMethod,
      retryOnAuthFailure: Boolean = true,
  ): HttpResponse {
    val url = hubSpotBaseUrl.resolve(path).toString()

    return try {
      val response =
          httpClient.request(url) {
            method = httpMethod
            bearerAuth(getAccessToken())
            if (body != null) {
              setBody(body)
            }
          }

      if (response.status == HttpStatusCode.Unauthorized && retryOnAuthFailure) {
        // Access token may have expired; fetch a new one and try this request again.
        accessToken = null
        doSendRequest(path, body, httpMethod, false)
      } else {
        response
      }
    } catch (e: ClientRequestException) {
      log.debug("HubSpot response ${e.response.status}: ${e.response.bodyAsText()}")
      throw e
    }
  }

  private inline fun <reified T> sendRequest(
      path: String,
      body: Any? = null,
      method: HttpMethod = if (body != null) HttpMethod.Post else HttpMethod.Get,
  ): T {
    ensureEnabled()

    return runBlocking {
      val response = doSendRequest(path, body, method)
      if (T::class.java == Unit.javaClass) {
        T::class.objectInstance!!
      } else {
        response.body()
      }
    }
  }

  private fun ensureEnabled() {
    if (!config.hubSpot.enabled) {
      throw IllegalStateException("HubSpot is not enabled")
    }
  }

  data class AccountDetailsResponse(
      val portalId: Long,
      val uiDomain: String,
  )

  data class Association(
      val to: To,
      val types: List<Type>,
  ) {
    constructor(to: String, typeId: Int) : this(To(to), listOf(Type(typeId)))

    data class To(
        val id: String,
    )

    data class Type(
        val associationTypeId: Int,
        val associationCategory: String = ASSOCIATION_CATEGORY,
    )
  }

  data class CreateContactRequest(
      val properties: Properties,
      val associations: List<Association>,
  ) {
    constructor(
        email: String,
        name: String?,
        dealId: String,
    ) : this(Properties(email, name), listOf(Association(dealId, ASSOCIATION_TYPE_CONTACT_TO_DEAL)))

    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class Properties(
        @JsonProperty(CONTACT_PROPERTY_EMAIL) val email: String,
        @JsonProperty(CONTACT_PROPERTY_FIRST_NAME) val firstName: String?,
        @JsonProperty(CONTACT_PROPERTY_FULL_NAME) val fullName: String? = firstName,
    )
  }

  data class CreateCompanyRequest(
      val properties: Properties,
      val associations: List<Association>,
  ) {
    constructor(
        name: String,
        website: String,
        dealId: String,
    ) : this(
        Properties(name, website),
        listOf(Association(dealId, ASSOCIATION_TYPE_COMPANY_TO_DEAL)),
    )

    data class Properties(
        @JsonProperty(COMPANY_PROPERTY_NAME) val name: String,
        @JsonProperty(COMPANY_PROPERTY_WEBSITE) val website: String,
    )
  }

  data class CreateDealRequest(val properties: Properties) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class Properties(
        @JsonProperty(DEAL_PROPERTY_APPLICATION_REFORESTABLE_LAND)
        val applicationReforestableLand: BigDecimal?,
        @JsonProperty(DEAL_PROPERTY_PROJECT_COUNTRY) val countryName: String?,
        @JsonProperty(DEAL_PROPERTY_NAME) val dealName: String,
        @JsonProperty(DEAL_PROPERTY_PIPELINE) val pipeline: String,
        @JsonProperty(DEAL_PROPERTY_STAGE) val stage: String,
    )
  }

  data class CreateDealResponse(val id: String)

  data class ResponseWithId(val id: String)

  data class GetProjectCountryPropertyResponse(val options: List<Option>) {
    data class Option(
        val label: String,
        val value: String,
    )
  }

  data class ListPipelinesResponse(val results: List<Pipeline>) {
    data class Pipeline(
        val id: String,
        val label: String,
        val stages: List<Stage>,
    ) {
      data class Stage(
          val id: String,
          val label: String,
      )
    }
  }

  data class SearchObjectsRequest(
      val filterGroups: List<FilterGroup>,
      val limit: Int = 1,
      val properties: List<String> = listOf("id"),
  ) {
    constructor(
        propertyName: String,
        operator: String,
        value: String,
    ) : this(listOf(FilterGroup(listOf(Filter(propertyName, operator, value)))))

    data class FilterGroup(
        val filters: List<Filter>,
    )

    data class Filter(
        val propertyName: String,
        val operator: String,
        val value: String,
    )
  }

  data class SearchObjectsResponse(val results: List<Result>) {
    data class Result(val id: String)
  }

  data class TokenResponse(
      @JsonProperty("access_token") val accessToken: String,
      @JsonProperty("refresh_token") val refreshToken: String,
      @JsonProperty("expires_in") val expiresIn: Long,
  )
}
