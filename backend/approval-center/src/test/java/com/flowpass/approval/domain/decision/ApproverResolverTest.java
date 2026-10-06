package com.flowpass.approval.domain.decision;

import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.directory.OrgDirectory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Domain tests for {@link ApproverResolver}: kinds, all-or-nothing, no self-approval. */
class ApproverResolverTest {

    private final TenantId tenant = new TenantId("t_1");
    private final ApproverRef initiator = new ApproverRef("USER", "u_1");

    /** A directory backed by plain maps. */
    private static OrgDirectory directory(Map<String, String> managers, Map<String, String> heads) {
        return new OrgDirectory() {
            @Override
            public Optional<String> managerOf(TenantId tenantId, String userId) {
                return Optional.ofNullable(managers.get(userId));
            }

            @Override
            public Optional<String> departmentHeadOf(TenantId tenantId, String userId) {
                return Optional.ofNullable(heads.get(userId));
            }

            @Override
            public Set<String> rolesOf(TenantId tenantId, String userId) {
                return Set.of();
            }
        };
    }

    private ApproverResolver.Resolution resolve(List<ApproverRef> entries, Map<String, Object> variables,
                                                OrgDirectory directory) {
        return ApproverResolver.resolve(tenant, entries, initiator, variables, directory);
    }

    private static ApproverRef entry(String type, String id) {
        return new ApproverRef(type, id);
    }

    @Test
    void usersAndRolesPassThrough() {
        var result = resolve(List.of(entry("USER", "u_2"), entry("ROLE", "finance")), Map.of(),
                directory(Map.of(), Map.of()));

        assertThat(result.approvers()).containsExactly(entry("USER", "u_2"), entry("ROLE", "finance"));
        assertThat(result.complete()).isTrue();
    }

    @Test
    void theInitiatorsManagerAndDepartmentHeadResolveToUsers() {
        var result = resolve(
                List.of(entry("INITIATOR_MANAGER", "initiator"), entry("DEPARTMENT_HEAD", "initiator")), Map.of(),
                directory(Map.of("u_1", "u_boss"), Map.of("u_1", "u_head")));

        assertThat(result.approvers()).containsExactly(entry("USER", "u_boss"), entry("USER", "u_head"));
    }

    @Test
    void aFormFieldNamesTheApprover() {
        var result = resolve(List.of(entry("FORM_FIELD", "reviewer")), Map.of("reviewer", " u_rev "),
                directory(Map.of(), Map.of()));

        assertThat(result.approvers()).containsExactly(entry("USER", "u_rev"));
    }

    @Test
    void aMissingManagerFailsTheWholeResolutionInsteadOfDroppingThatApprover() {
        var result = resolve(List.of(entry("USER", "u_fin"), entry("INITIATOR_MANAGER", "initiator")), Map.of(),
                directory(Map.of(), Map.of()));

        assertThat(result.approvers()).as("never proceed with fewer approvers than intended").isEmpty();
        assertThat(result.problems()).containsExactly("no manager recorded for u_1");
        assertThat(result.complete()).isFalse();
    }

    @Test
    void aMissingDepartmentHeadFailsResolution() {
        var result = resolve(List.of(entry("DEPARTMENT_HEAD", "initiator")), Map.of(), directory(Map.of(), Map.of()));

        assertThat(result.problems()).containsExactly("no department head recorded for u_1");
    }

    @Test
    void anEmptyOrMissingFormFieldFailsResolution() {
        assertThat(resolve(List.of(entry("FORM_FIELD", "reviewer")), Map.of("reviewer", "  "),
                directory(Map.of(), Map.of())).problems())
                .containsExactly("form field 'reviewer' does not name an approver");
        assertThat(resolve(List.of(entry("FORM_FIELD", "reviewer")), null, directory(Map.of(), Map.of())).problems())
                .hasSize(1);
    }

    @Test
    void anUnknownKindFailsResolution() {
        var result = resolve(List.of(entry("TEAM_LEAD", "x")), Map.of(), directory(Map.of(), Map.of()));

        assertThat(result.problems()).containsExactly("unknown approver type 'TEAM_LEAD'");
    }

    @Test
    void allProblemsAreReportedTogether() {
        var result = resolve(List.of(entry("INITIATOR_MANAGER", "initiator"), entry("FORM_FIELD", "reviewer")), Map.of(),
                directory(Map.of(), Map.of()));

        assertThat(result.problems()).hasSize(2);
    }

    @Test
    void theInitiatorNeverApprovesTheirOwnRequest() {
        var result = resolve(List.of(entry("USER", "u_1"), entry("USER", "u_2")), Map.of(),
                directory(Map.of(), Map.of()));

        assertThat(result.approvers()).containsExactly(entry("USER", "u_2"));
    }

    @Test
    void anInitiatorWhoIsTheirOwnManagerIsDroppedAndNobodyIsLeft() {
        var result = resolve(List.of(entry("INITIATOR_MANAGER", "initiator")), Map.of(),
                directory(Map.of("u_1", "u_1"), Map.of()));

        assertThat(result.approvers()).isEmpty();
        assertThat(result.problems()).as("not a failure: it simply routes to manual handling").isEmpty();
    }

    @Test
    void duplicatesAreRemovedAndOrderIsKept() {
        var result = resolve(
                List.of(entry("USER", "u_boss"), entry("INITIATOR_MANAGER", "initiator"), entry("USER", "u_2")),
                Map.of(), directory(Map.of("u_1", "u_boss"), Map.of()));

        assertThat(result.approvers()).containsExactly(entry("USER", "u_boss"), entry("USER", "u_2"));
    }

    @Test
    void aRoleIsNotExpandedAtStartSoMembershipIsCheckedWhenSomebodyActs() {
        var result = resolve(List.of(entry("ROLE", "finance")), Map.of(), directory(Map.of(), Map.of()));

        assertThat(result.approvers()).containsExactly(entry("ROLE", "finance"));
    }
}
