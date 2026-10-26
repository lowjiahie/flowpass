package com.flowpass.approval.domain.decision;

import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.directory.OrgDirectory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Domain service: turn the approver entries of a matched rule into the principals who will receive tasks, round
 * by round.
 *
 * <p>Rules that keep the outcome safe and predictable:</p>
 * <ul>
 *   <li><b>All or nothing.</b> If any entry of any round cannot be resolved (no manager recorded, the form field
 *       is empty, an unknown kind) the result carries the problems and <em>no</em> approvers — the request must
 *       not proceed with fewer approvers than the rule intended; the caller routes it to manual handling.</li>
 *   <li><b>No self-approval.</b> A resolved user who is the initiator is dropped, in every round. If that leaves
 *       nobody at all, the request goes to manual handling — it is never auto-approved.</li>
 *   <li><b>Nobody approves twice.</b> A principal who already appears in an earlier round is dropped from later
 *       ones; a round left empty is removed and the later rounds move up.</li>
 *   <li>Duplicates inside a round are removed, order is kept.</li>
 * </ul>
 * Deterministic and pure: the same rule, initiator, variables and directory give the same answer.
 */
public final class ApproverResolver {

    /** How many levels up {@code INITIATOR_MANAGER} may reach. */
    public static final int MAX_MANAGER_LEVEL = 5;

    private ApproverResolver() {
    }

    /** Resolved principals per round, or the reasons resolution failed (then there are no rounds). */
    public record Resolution(List<List<ApproverRef>> rounds, List<String> problems) {

        public Resolution {
            rounds = rounds.stream().map(List::copyOf).toList();
            problems = List.copyOf(problems);
        }

        /** True when every entry resolved. */
        public boolean complete() {
            return problems.isEmpty();
        }

        /** Everyone, across all rounds, in order. */
        public List<ApproverRef> approvers() {
            return rounds.stream().flatMap(List::stream).toList();
        }
    }

    /** Resolve a single round (the plain "these people approve" case). */
    public static Resolution resolve(
            TenantId tenantId,
            List<ApproverRef> entries,
            ApproverRef initiator,
            Map<String, Object> variables,
            OrgDirectory directory) {
        return resolveRounds(tenantId, List.of(entries), initiator, variables, directory);
    }

    public static Resolution resolveRounds(
            TenantId tenantId,
            List<List<ApproverRef>> roundEntries,
            ApproverRef initiator,
            Map<String, Object> variables,
            OrgDirectory directory) {
        List<String> problems = new ArrayList<>();
        List<LinkedHashSet<ApproverRef>> resolved = new ArrayList<>();

        for (List<ApproverRef> entries : roundEntries) {
            LinkedHashSet<ApproverRef> round = new LinkedHashSet<>();
            for (ApproverRef entry : entries) {
                resolveEntry(tenantId, entry, initiator, variables, directory, round, problems);
            }
            resolved.add(round);
        }

        if (!problems.isEmpty()) {
            return new Resolution(List.of(), problems);
        }

        Set<ApproverRef> seen = new HashSet<>();
        List<List<ApproverRef>> rounds = new ArrayList<>();
        for (LinkedHashSet<ApproverRef> round : resolved) {
            List<ApproverRef> kept = new ArrayList<>();
            for (ApproverRef approver : round) {
                if (!isInitiator(approver, initiator) && seen.add(approver)) {
                    kept.add(approver);
                }
            }
            if (!kept.isEmpty()) {
                rounds.add(kept);
            }
        }
        return new Resolution(rounds, List.of());
    }

    private static void resolveEntry(
            TenantId tenantId,
            ApproverRef entry,
            ApproverRef initiator,
            Map<String, Object> variables,
            OrgDirectory directory,
            LinkedHashSet<ApproverRef> round,
            List<String> problems) {
        Optional<ApproverKind> kind = ApproverKind.parse(entry.type());
        if (kind.isEmpty()) {
            problems.add("unknown approver type '" + entry.type() + "'");
            return;
        }
        switch (kind.get()) {
            case USER -> round.add(new ApproverRef("USER", entry.id()));
            case ROLE -> round.add(new ApproverRef("ROLE", entry.id()));
            case INITIATOR_MANAGER -> managerAtLevel(tenantId, entry, initiator, directory, problems)
                    .ifPresent(manager -> round.add(new ApproverRef("USER", manager)));
            case DEPARTMENT_HEAD -> directory.departmentHeadOf(tenantId, initiator.id())
                    .ifPresentOrElse(
                            head -> round.add(new ApproverRef("USER", head)),
                            () -> problems.add("no department head recorded for " + initiator.id()));
            case FORM_FIELD -> {
                Object value = variables == null ? null : variables.get(entry.id());
                String user = value == null ? "" : String.valueOf(value).trim();
                if (user.isEmpty()) {
                    problems.add("form field '" + entry.id() + "' does not name an approver");
                } else {
                    round.add(new ApproverRef("USER", user));
                }
            }
        }
    }

    /**
     * The initiator's manager, or — when the entry's id is a number N — the manager N levels up (2 = the manager's
     * manager). Any other id means one level. A reporting loop or a missing link is a problem, never a guess.
     */
    private static Optional<String> managerAtLevel(
            TenantId tenantId, ApproverRef entry, ApproverRef initiator, OrgDirectory directory, List<String> problems) {
        int level = 1;
        if (entry.id().chars().allMatch(Character::isDigit)) {
            level = Integer.parseInt(entry.id().length() > 3 ? "999" : entry.id());
            if (level < 1 || level > MAX_MANAGER_LEVEL) {
                problems.add("manager level must be between 1 and " + MAX_MANAGER_LEVEL + " but was " + entry.id());
                return Optional.empty();
            }
        }
        String current = initiator.id();
        Set<String> chain = new HashSet<>();
        chain.add(current);
        for (int step = 1; step <= level; step++) {
            Optional<String> next = directory.managerOf(tenantId, current);
            if (next.isEmpty()) {
                problems.add(step == 1
                        ? "no manager recorded for " + current
                        : "the reporting line of " + initiator.id() + " ends at " + current
                                + " before level " + level);
                return Optional.empty();
            }
            if (!chain.add(next.get())) {
                problems.add("the reporting line of " + initiator.id() + " loops back to " + next.get());
                return Optional.empty();
            }
            current = next.get();
        }
        return Optional.of(current);
    }

    private static boolean isInitiator(ApproverRef approver, ApproverRef initiator) {
        return "USER".equals(approver.type()) && approver.id().equals(initiator.id());
    }
}
