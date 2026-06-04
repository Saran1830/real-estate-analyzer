package com.compliance.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ComplianceAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(ComplianceAgentApplication.class, args);
    }
}
