package com.flowpass.approval.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Real, valid process definitions for tests (the engine now runs the stored definition, not a classpath file). */
public final class TestBpmn {

    private static final String BUILT_IN_KEY = "purchase_request";

    private TestBpmn() {
    }

    /** The platform's built-in purchase-request definition, with its process id renamed to {@code key}. */
    public static String forKey(String key) {
        try (InputStream in = TestBpmn.class.getResourceAsStream("/processes/" + BUILT_IN_KEY + ".bpmn20.xml")) {
            String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return BUILT_IN_KEY.equals(key) ? xml : xml.replace(BUILT_IN_KEY, key);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String purchaseRequest() {
        return forKey(BUILT_IN_KEY);
    }

    /**
     * Seed a template with one published version (the built-in definition) for a tenant, the way a
     * publish would have left it.
     */
    public static void seedPublished(JdbcTemplate jdbc, String tenant, String key, int version) {
        String templateId = "tpl-" + tenant + "-" + key;
        jdbc.update("INSERT INTO approval_template (id, tenant_id, template_key, status, latest_version) "
                + "VALUES (?, ?, ?, 'PUBLISHED', ?)", templateId, tenant, key, version);
        for (int v = 1; v <= version; v++) {
            jdbc.update("INSERT INTO process_definition (id, template_id, version, bpmn_xml, checksum) "
                    + "VALUES (?, ?, ?, ?, ?)", "pd-" + tenant + "-" + key + "-" + v, templateId, v, forKey(key), "chk-" + v);
        }
    }
}
