package com.menora.initializr.observability;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled}: the generation audit's nightly retention
 * ({@link GenerationAuditService#applyRetention}) is the application's only scheduled job.
 */
@Configuration
@EnableScheduling
public class AuditScheduling {
}
