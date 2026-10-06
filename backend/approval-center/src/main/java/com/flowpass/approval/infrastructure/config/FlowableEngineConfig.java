package com.flowpass.approval.infrastructure.config;

import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.spring.boot.EngineConfigurationConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Process-engine behaviour the approval flow depends on.
 *
 * <p>By default Flowable 7 completes a parallel multi-instance step <em>asynchronously</em>: every task that
 * is not the last to finish schedules a completion job, which the async executor runs in another
 * transaction. That races the approver's own transaction — optimistic-locking failures, lost completion
 * counts, and a step that cannot be cancelled once part of it has finished. The approval use cases are
 * synchronous (the engine call, the business task and the instance state commit together), so the step is
 * completed in the caller's transaction instead.</p>
 */
@Configuration
public class FlowableEngineConfig {

    @Bean
    public EngineConfigurationConfigurer<SpringProcessEngineConfiguration> approvalProcessEngineConfigurer() {
        return configuration -> configuration.setParallelMultiInstanceAsyncLeave(false);
    }
}
