package com.campussphere.backend.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="app.registration.expiry-enabled",havingValue="true")
class RegistrationExpiry {
    private final CollegeRegistrationService service;
    RegistrationExpiry(CollegeRegistrationService service) { this.service=service; }
    @Scheduled(fixedDelay=60000)
    void sweep() { service.expire(); }
}