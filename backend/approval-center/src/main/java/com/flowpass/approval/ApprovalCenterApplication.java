package com.flowpass.approval;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Approval Center — the generic multi-tenant approval workflow service.
 *
 * <p>Entry point for the {@code approval-center} deployment unit. Business code lives in four DDD
 * layers ({@code interfaces} / {@code application} / {@code domain} / {@code infrastructure});
 * dependency direction is enforced by ArchUnit and must never point outward.</p>
 */
@SpringBootApplication
@EnableTransactionManagement
@ConfigurationPropertiesScan
public class ApprovalCenterApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApprovalCenterApplication.class, args);
    }
}
