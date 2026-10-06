package com.flowpass.approval.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * MyBatis mapper for {@code approval_template} and {@code process_definition}. Explicit SQL keeps the
 * tenant filter visible. Mappers are infrastructure only — never referenced by domain.
 */
@Mapper
public interface TemplateMapper {

    @Insert("""
            INSERT INTO approval_template (id, tenant_id, template_key, status, draft_bpmn, draft_form_schema, latest_version)
            VALUES (#{id}, #{tenantId}, #{templateKey}, #{status}, #{draftBpmn}, #{draftFormSchema}, #{latestVersion})
            """)
    void insert(ApprovalTemplateRow row);

    @Select("""
            SELECT id, tenant_id, template_key, status, draft_bpmn, draft_form_schema, latest_version
            FROM approval_template
            WHERE tenant_id = #{tenantId} AND template_key = #{templateKey}
            """)
    ApprovalTemplateRow findByKey(@Param("tenantId") String tenantId, @Param("templateKey") String templateKey);

    @Select("""
            SELECT id, tenant_id, template_key, status, draft_bpmn, draft_form_schema, latest_version
            FROM approval_template
            WHERE tenant_id = #{tenantId}
            ORDER BY template_key ASC
            """)
    List<ApprovalTemplateRow> findAll(@Param("tenantId") String tenantId);

    @Update("""
            UPDATE approval_template
            SET draft_bpmn = #{draftBpmn}, updated_at = now()
            WHERE id = #{id}
            """)
    void updateDraft(@Param("id") String id, @Param("draftBpmn") String draftBpmn);

    @Update("""
            UPDATE approval_template
            SET draft_form_schema = #{draftFormSchema}, updated_at = now()
            WHERE id = #{id}
            """)
    void updateDraftFormSchema(@Param("id") String id, @Param("draftFormSchema") String draftFormSchema);

    @Insert("""
            INSERT INTO form_schema (id, template_id, version, json_schema)
            VALUES (#{id}, #{templateId}, #{version}, #{jsonSchema})
            """)
    void insertFormSchema(@Param("id") String id, @Param("templateId") String templateId,
                          @Param("version") int version, @Param("jsonSchema") String jsonSchema);

    /** Compare-and-set on the version counter: succeeds only if nobody published since this was read. */
    @Update("""
            UPDATE approval_template
            SET status = #{status}, latest_version = #{latestVersion}, updated_at = now()
            WHERE id = #{id} AND latest_version = #{latestVersion} - 1
            """)
    int markPublished(@Param("id") String id, @Param("status") String status,
                      @Param("latestVersion") int latestVersion);

    @Update("""
            UPDATE approval_template
            SET status = #{status}, updated_at = now()
            WHERE id = #{id}
            """)
    void updateStatus(@Param("id") String id, @Param("status") String status);

    @Insert("""
            INSERT INTO process_definition
                (id, template_id, version, bpmn_xml, checksum, published_by_type, published_by_id)
            VALUES
                (#{id}, #{templateId}, #{version}, #{bpmnXml}, #{checksum}, #{publishedByType}, #{publishedById})
            """)
    void insertDefinition(ProcessDefinitionRow row);
}
