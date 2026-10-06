package com.flowpass.approval.application.query;

import com.flowpass.approval.application.port.ApproverResolution;
import com.flowpass.approval.application.port.CandidateApprover;
import com.flowpass.approval.application.port.CandidateResolverPort;
import com.flowpass.approval.contract.dto.ApproverDTO;
import com.flowpass.approval.contract.dto.ApproverPreviewDTO;
import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** Unit tests for {@link PreviewApproversHandler}: the dry run reports the same resolution a start would use. */
@ExtendWith(MockitoExtension.class)
class PreviewApproversHandlerTest {

    private final TenantId tenant = new TenantId("t_10086");
    private final ApproverRef initiator = new ApproverRef("USER", "u_1");

    @Mock
    private CandidateResolverPort candidateResolver;

    private PreviewApproversHandler handler() {
        return new PreviewApproversHandler(candidateResolver);
    }

    @Test
    void mapsResolvedCandidatesToDtos() {
        when(candidateResolver.resolve(tenant, "purchase_request", initiator, Map.of("amount", 5000)))
                .thenReturn(new ApproverResolution(
                        List.of(new CandidateApprover("USER", "u_big"), new CandidateApprover("ROLE", "finance")),
                        List.of()));

        ApproverPreviewDTO result = handler().handle(
                new PreviewApproversQuery(tenant, "purchase_request", initiator, Map.of("amount", 5000)));

        assertThat(result.approvers()).containsExactly(new ApproverDTO("USER", "u_big"), new ApproverDTO("ROLE", "finance"));
        assertThat(result.problems()).isEmpty();
    }

    @Test
    void reportsWhyResolutionFailedInsteadOfHidingIt() {
        when(candidateResolver.resolve(tenant, "purchase_request", initiator, Map.of()))
                .thenReturn(new ApproverResolution(List.of(), List.of("no manager recorded for u_1")));

        ApproverPreviewDTO result = handler().handle(
                new PreviewApproversQuery(tenant, "purchase_request", initiator, Map.of()));

        assertThat(result.approvers()).isEmpty();
        assertThat(result.problems()).containsExactly("no manager recorded for u_1");
    }

    @Test
    void treatsAbsentVariablesAsEmpty() {
        when(candidateResolver.resolve(tenant, "purchase_request", initiator, Map.of()))
                .thenReturn(ApproverResolution.none());

        assertThat(handler().handle(new PreviewApproversQuery(tenant, "purchase_request", initiator, null))
                .approvers()).isEmpty();
    }
}
