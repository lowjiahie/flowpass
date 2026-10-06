package com.flowpass.approval.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * MyBatis mapper for resolving the latest published template definition. Explicit SQL keeps the
 * tenant filter visible. Mappers are infrastructure only — never referenced by domain.
 */
@Mapper
public interface DefinitionMapper {

    @Select("""
            SELECT t.id AS template_id, t.template_key, pd.version AS definition_version,
                   (SELECT fs.version FROM form_schema fs WHERE fs.template_id = t.id AND fs.version = pd.version) AS form_version,
                   pd.checksum, t.status
            FROM approval_template t
            JOIN process_definition pd ON pd.template_id = t.id
            WHERE t.tenant_id = #{tenantId} AND t.template_key = #{templateKey}
              AND t.status = 'PUBLISHED'
            ORDER BY pd.version DESC
            LIMIT 1
            """)
    PublishedDefinitionRow findPublished(@Param("tenantId") String tenantId, @Param("templateKey") String templateKey);

    @Select("""
            SELECT pd.version, pd.checksum, pd.created_at, pd.published_by_type, pd.published_by_id
            FROM process_definition pd
            JOIN approval_template t ON t.id = pd.template_id
            WHERE t.tenant_id = #{tenantId} AND t.template_key = #{templateKey}
            ORDER BY pd.version DESC
            """)
    List<ProcessDefinitionRow> findVersions(@Param("tenantId") String tenantId,
                                            @Param("templateKey") String templateKey);

    @Select("""
            SELECT fs.json_schema
            FROM form_schema fs
            JOIN approval_template t ON t.id = fs.template_id
            WHERE t.tenant_id = #{tenantId} AND t.template_key = #{templateKey} AND fs.version = #{version}
            """)
    String findFormSchema(@Param("tenantId") String tenantId, @Param("templateKey") String templateKey,
                          @Param("version") int version);

    @Select("""
            SELECT pd.bpmn_xml
            FROM process_definition pd
            JOIN approval_template t ON t.id = pd.template_id
            WHERE t.tenant_id = #{tenantId} AND t.template_key = #{templateKey} AND pd.version = #{version}
            """)
    String findBpmn(@Param("tenantId") String tenantId, @Param("templateKey") String templateKey,
                    @Param("version") int version);
}
