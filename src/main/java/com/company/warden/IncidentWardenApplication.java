package com.company.warden;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Main Spring Boot application entry point. */
@SpringBootApplication
@EnableCaching
@EnableScheduling
public class IncidentWardenApplication {

    public static void main(String[] args) {
        SpringApplication.run(IncidentWardenApplication.class, args);
    }
}