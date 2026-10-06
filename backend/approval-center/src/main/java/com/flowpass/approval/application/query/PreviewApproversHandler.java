package com.flowpass.approval.application.query;

import com.flowpass.approval.application.common.Permissions;
import com.flowpass.approval.application.port.ApproverResolution;
import com.flowpass.approval.application.port.CandidateResolverPort;
import com.flowpass.approval.contract.dto.ApproverDTO;
import com.flowpass.approval.contract.dto.ApproverPreviewDTO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Dry-run use case: resolve the approvers a start would assign, without creating anything.
 *
 * <p>Delegates to the same {@link CandidateResolverPort} the start use case uses, so the preview cannot
 * drift from the real resolution — the matrix logic stays in the decision domain and is never
 * duplicated in the UI. Resolution problems are reported, not hidden: they are exactly what would route a
 * real start to manual review.</p>
 */
@Service
public class PreviewApproversHandler {

    private final CandidateResolverPort candidateResolver;

    public PreviewApproversHandler(CandidateResolverPort candidateResolver) {
        this.candidateResolver = candidateResolver;
    }

    @PreAuthorize("hasAuthority('" + Permissions.RULE_MANAGE + "')")
    @Transactional(readOnly = true)
    public ApproverPreviewDTO handle(PreviewApproversQuery query) {
        ApproverResolution resolution = candidateResolver.resolve(
                query.tenantId(),
                query.templateKey(),
                query.initiator(),
                query.variables() == null ? Map.of() : query.variables());
        return new ApproverPreviewDTO(
                resolution.approvers().stream()
                        .map(candidate -> new ApproverDTO(candidate.type(), candidate.id()))
                        .toList(),
                resolution.problems());
    }
}
