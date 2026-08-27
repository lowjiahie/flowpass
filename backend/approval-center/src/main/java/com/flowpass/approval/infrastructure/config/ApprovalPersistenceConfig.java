package com.flowpass.approval.infrastructure.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis configuration — scans mappers in the infrastructure persistence package.
 *
 * <p>The mapper interfaces are the adapter boundary for the {@code ApprovalInstanceRepository} port;
 * they never leak into {@code domain} (which depends only on the port).</p>
 */
@Configuration
@MapperScan("com.flowpass.approval.infrastructure.persistence")
public class ApprovalPersistenceConfig {
}
