package com.flowpass.approval.application.port;

import java.util.List;

/**
 * The outcome of resolving who must approve a started request.
 *
 * @param approvers the principals that receive tasks — empty when nobody could be resolved
 * @param problems  why resolution failed (then {@code approvers} is empty and the request goes to manual
 *                  handling); empty on success. Also empty when no rule matched at all.
 */
public record ApproverResolution(List<CandidateApprover> approvers, List<String> problems) {

    public ApproverResolution {
        approvers = approvers == null ? List.of() : List.copyOf(approvers);
        problems = problems == null ? List.of() : List.copyOf(problems);
    }

    public static ApproverResolution none() {
        return new ApproverResolution(List.of(), List.of());
    }

    /** True when resolution failed (as opposed to simply matching no rule). */
    public boolean failed() {
        return !problems.isEmpty();
    }
}
