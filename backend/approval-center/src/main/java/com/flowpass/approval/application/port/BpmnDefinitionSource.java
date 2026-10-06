package com.flowpass.approval.application.port;

/**
 * Outbound port for resolving the BPMN definition XML behind a template key.
 *
 * <p>The publish use case needs the raw process definition to snapshot it (and compute its checksum)
 * without reaching into classpath/Spring resource loading itself. Adapters supply the XML; the
 * application layer stays framework-agnostic.</p>
 */
public interface BpmnDefinitionSource {

    /**
     * Load the BPMN XML for a template key.
     *
     * @throws IllegalStateException when no definition exists for the key or it cannot be read
     */
    String load(String templateKey);
}
