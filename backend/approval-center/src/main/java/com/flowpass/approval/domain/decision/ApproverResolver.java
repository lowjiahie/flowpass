package com.flowpass.approval.domain.decision;

import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.common.TenantId;
import com.flowpass.approval.domain.directory.OrgDirectory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Domain service: turn the approver entries of a matched rule into the principals who will receive tasks.
 *
 * <p>Rules that keep the outcome safe and predictable:</p>
 * <ul>
 *   <li><b>All or nothing.</b> If any entry cannot be resolved (no manager recorded, the form field is empty,
 *       an unknown kind) the result carries the problems and <em>no</em> approvers — the request must not
 *       proceed with fewer approvers than the rule intended; the caller routes it to manual handling.</li>
 *   <li><b>No self-approval.</b> A resolved user who is the initiator is dropped (an initiator who is also
 *       the manager does not approve their own request). If that leaves nobody, the request goes to manual
 *       handling — it is never auto-approved.</li>
 *   <li>Duplicates are removed, order is kept.</li>
 * </ul>
 * Deterministic and pure: the same rule, initiator, variables and directory give the same answer.
 */
public final class ApproverResolver {

    private ApproverResolver() {
    }

    /** Resolved principals, or the reasons resolution failed (then {@code approvers} is empty). */
    public record Resolution(List<ApproverRef> approvers, List<String> problems) {

        public Resolution {
            approvers = List.copyOf(approvers);
            problems = List.copyOf(problems);
        }

        /** True when every entry resolved. */
        public boolean complete() {
            return problems.isEmpty();
        }
    }

    public static Resolution resolve(
            TenantId tenantId,
            List<ApproverRef> entries,
            ApproverRef initiator,
            Map<String, Object> variables,
            OrgDirectory directory) {
        List<String> problems = new ArrayList<>();
        LinkedHashSet<ApproverRef> resolved = new LinkedHashSet<>();

        for (ApproverRef entry : entries) {
            Optional<ApproverKind> kind = ApproverKind.parse(entry.type());
            if (kind.isEmpty()) {
                problems.add("unknown approver type '" + entry.type() + "'");
                continue;
            }
            switch (kind.get()) {
                case USER -> resolved.add(new ApproverRef("USER", entry.id()));
                case ROLE -> resolved.add(new ApproverRef("ROLE", entry.id()));
                case INITIATOR_MANAGER -> directory.managerOf(tenantId, initiator.id())
                        .ifPresentOrElse(
                                manager -> resolved.add(new ApproverRef("USER", manager)),
                                () -> problems.add("no manager recorded for " + initiator.id()));
                case DEPARTMENT_HEAD -> directory.departmentHeadOf(tenantId, initiator.id())
                        .ifPresentOrElse(
                                head -> resolved.add(new ApproverRef("USER", head)),
                                () -> problems.add("no department head recorded for " + initiator.id()));
                case FORM_FIELD -> {
                    Object value = variables == null ? null : variables.get(entry.id());
                    String user = value == null ? "" : String.valueOf(value).trim();
                    if (user.isEmpty()) {
                        problems.add("form field '" + entry.id() + "' does not name an approver");
                    } else {
                        resolved.add(new ApproverRef("USER", user));
                    }
                }
            }
        }

        if (!problems.isEmpty()) {
            return new Resolution(List.of(), problems);
        }
        List<ApproverRef> approvers = resolved.stream()
                .filter(approver -> !isInitiator(approver, initiator))
                .toList();
        return new Resolution(approvers, List.of());
    }

    private static boolean isInitiator(ApproverRef approver, ApproverRef initiator) {
        return "USER".equals(approver.type()) && approver.id().equals(initiator.id());
    }
}
