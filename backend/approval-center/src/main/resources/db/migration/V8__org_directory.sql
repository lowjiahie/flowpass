-- ============================================================================
-- V8: temporary organisation directory (who reports to whom, department heads, role members).
-- TODO(keycloak): organisation data belongs to the identity platform. Until Keycloak provides it, the
-- approval service reads it from these tenant-scoped tables through the OrgDirectory port; replacing the
-- adapter replaces the tables. There is no write API on purpose — seed them (SQL) for local use.
-- ============================================================================

CREATE TABLE directory_user (
    tenant_id     VARCHAR(64)  NOT NULL,
    user_id       VARCHAR(128) NOT NULL,
    manager_id    VARCHAR(128),
    department_id VARCHAR(64),
    PRIMARY KEY (tenant_id, user_id)
);

CREATE TABLE directory_department (
    tenant_id     VARCHAR(64)  NOT NULL,
    department_id VARCHAR(64)  NOT NULL,
    head_user_id  VARCHAR(128),
    PRIMARY KEY (tenant_id, department_id)
);

CREATE TABLE directory_role_member (
    tenant_id VARCHAR(64)  NOT NULL,
    role_name VARCHAR(64)  NOT NULL,
    user_id   VARCHAR(128) NOT NULL,
    PRIMARY KEY (tenant_id, role_name, user_id)
);

CREATE INDEX idx_directory_role_member_user ON directory_role_member (tenant_id, user_id);
