package com.flowpass.approval.infrastructure.workflow;

import com.flowpass.approval.application.port.BpmnDefinitionSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Classpath-backed {@link BpmnDefinitionSource}: loads {@code processes/{key}.bpmn20.xml} so a
 * template can be published with the exact definition it will run against.
 *
 * <p>A template key without a classpath definition is not publishable (mirrors the Flowable adapter's
 * lazy deployment contract).</p>
 */
@Component
public class ClasspathBpmnDefinitionSource implements BpmnDefinitionSource {

    private final ResourceLoader resourceLoader;

    public ClasspathBpmnDefinitionSource(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @Override
    public String load(String templateKey) {
        Resource resource = resourceLoader.getResource("classpath:processes/" + templateKey + ".bpmn20.xml");
        if (!resource.exists()) {
            throw new IllegalStateException("No BPMN definition for template key " + templateKey);
        }
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read BPMN for " + templateKey, e);
        }
    }
}
