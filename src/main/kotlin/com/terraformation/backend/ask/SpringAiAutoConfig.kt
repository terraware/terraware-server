package com.terraformation.backend.ask

import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration
import org.springframework.context.annotation.Configuration

/**
 * Enables configuration of OpenAI client classes if there is an OpenAI API key set in the
 * environment or in the application properties. By default, Spring Boot will try to create all the
 * beans and will bomb out if the creation fails, which it will if there's no API key configured.
 *
 * We don't want to require dev environments to set OpenAI API keys, so we configure Spring to
 * ignore the auto-configuration classes (this is done via an exclude rule in `application.yaml`)
 * and subclass them here, with an additional rule to make them conditional on the presence of the
 * API key. This gives us the easy configuration of the Spring Boot starter packages without the
 * "bomb out if there's no API key" behavior.
 *
 * TODO: If/when we decide to make OpenAI integration a core part of Terraware rather than just an
 *   experiment, we'll want to get rid of this setup and make the API key required everywhere.
 */
@ConditionalOnSpringAi
@Configuration
class OpenAiChatConditionalAutoConfig : OpenAiChatAutoConfiguration()

@ConditionalOnSpringAi
@Configuration
class OpenAiEmbeddingConditionalAutoConfig : OpenAiEmbeddingAutoConfiguration()

@ConditionalOnSpringAi
@Configuration
class PgVectorStoreConditionalAutoConfig : PgVectorStoreAutoConfiguration()
