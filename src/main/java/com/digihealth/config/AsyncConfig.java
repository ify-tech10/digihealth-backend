package com.digihealth.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/** @Async (emails are sent in the background) and @Scheduled (nightly clean-up). */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {
}
