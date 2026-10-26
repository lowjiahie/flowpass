package com.flowpass.approval.application.port;

import java.util.List;

/**
 * The outcome of resolving who must approve a started request.
 *
 * @param approvers everyone who receives a task, across all rounds, in order — empty when nobody could be resolved
 * @param rounds    the approval chain: the principals of round 1, then round 2, and so on (no empty round)
 * @param problems  why resolution failed (then there are no approvers and the request goes to manual handling);
 *                  empty on success. Also empty when no rule matched at all.
 */
public record ApproverResolution(List<CandidateApprover> approvers, List<List<CandidateApprover>> rounds,
                                 List<String> problems) {

    public ApproverResolution {
        approvers = approvers == null ? List.of() : List.copyOf(approvers);
        rounds = rounds == null ? List.of() : rounds.stream().map(List::copyOf).toList();
        problems = problems == null ? List.of() : List.copyOf(problems);
    }

    /** A single round of approvers. */
    public ApproverResolution(List<CandidateApprover> approvers, List<String> problems) {
        this(approvers, approvers == null || approvers.isEmpty() ? List.of() : List.of(approvers), problems);
    }

    /** An approval chain of several rounds. */
    public static ApproverResolution ofRounds(List<List<CandidateApprover>> rounds, List<String> problems) {
        List<List<CandidateApprover>> safe = rounds == null ? List.of() : rounds;
        return new ApproverResolution(safe.stream().flatMap(List::stream).toList(), safe, problems);
    }

    public static ApproverResolution none() {
        return new ApproverResolution(List.of(), List.of(), List.of());
    }

    /** True when resolution failed (as opposed to simply matching no rule). */
    public boolean failed() {
        return !problems.isEmpty();
    }
}
