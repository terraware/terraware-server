package com.terraformation.backend.ask

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

/** Annotation for beans that should only be instantiated if Spring AI is configured. */
@ConditionalOnProperty("spring.ai.openai.api-key") annotation class ConditionalOnSpringAi
