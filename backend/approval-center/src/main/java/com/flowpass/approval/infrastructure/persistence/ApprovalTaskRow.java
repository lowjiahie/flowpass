package com.flowpass.approval.infrastructure.persistence;

/** DB row for {@code approval_task}. Maps 1:1 to the mapper insert. */
public class ApprovalTaskRow {

    private String id;
    private String tenantId;
    private String instanceId;
    private String name;
    private String assigneeType;
    private String assigneeId;
    private String status;
    private long version;
    private String nodeKey;
    private String workflowTaskRef;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAssigneeType() {
        return assigneeType;
    }

    public void setAssigneeType(String assigneeType) {
        this.assigneeType = assigneeType;
    }

    public String getAssigneeId() {
        return assigneeId;
    }

    public void setAssigneeId(String assigneeId) {
        this.assigneeId = assigneeId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public String getNodeKey() {
        return nodeKey;
    }

    public void setNodeKey(String nodeKey) {
        this.nodeKey = nodeKey;
    }

    public String getWorkflowTaskRef() {
        return workflowTaskRef;
    }

    public void setWorkflowTaskRef(String workflowTaskRef) {
        this.workflowTaskRef = workflowTaskRef;
    }
}
