package com.flowpass.approval.infrastructure.candidate;

import com.flowpass.approval.application.port.ApproverResolution;
import com.flowpass.approval.application.port.CandidateApprover;
import com.flowpass.approval.application.port.CandidateResolverPort;
import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.decision.ApprovalMatrix;
import com.flowpass.approval.domain.decision.ApproverResolver;
import com.flowpass.approval.domain.decision.DecisionRuleRepository;
import com.flowpass.approval.domain.directory.OrgDirectory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Decision-context {@link CandidateResolverPort}: resolves first-round approvers from the tenant's
 * approval-matrix rules.
 *
 * <p>The {@code ApprovalMatrix} domain service selects the highest-priority matching rule and the
 * {@code ApproverResolver} domain service turns its approver entries into principals using the
 * organisation directory. An instance whose template has no matching rule starts with no first-round task
 * (manual handling) — resolution is deterministic and repeatable.</p>
 */
@Component
public class MatrixCandidateResolverAdapter implements CandidateResolverPort {

    private final DecisionRuleRepository ruleRepository;
    private final OrgDirectory orgDirectory;

    public MatrixCandidateResolverAdapter(DecisionRuleRepository ruleRepository, OrgDirectory orgDirectory) {
        this.ruleRepository = ruleRepository;
        this.orgDirectory = orgDirectory;
    }

    @Override
    public ApproverResolution resolve(TenantId tenantId, String templateKey, ApproverRef initiator,
                                      Map<String, Object> variables) {
        return ApprovalMatrix.select(ruleRepository.findByTemplate(tenantId, templateKey), variables)
                .map(rule -> {
                    ApproverResolver.Resolution resolution = ApproverResolver.resolve(
                            tenantId, rule.approvers(), initiator, variables, orgDirectory);
                    return new ApproverResolution(
                            resolution.approvers().stream()
                                    .map(approver -> new CandidateApprover(approver.type(), approver.id()))
                                    .toList(),
                            resolution.problems());
                })
                .orElseGet(ApproverResolution::none);
    }
}
