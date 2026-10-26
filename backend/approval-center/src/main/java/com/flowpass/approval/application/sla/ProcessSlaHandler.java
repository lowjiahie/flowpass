package com.flowpass.approval.application.sla;

import com.flowpass.approval.domain.task.DueTaskRef;
import com.flowpass.approval.domain.task.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * The deadline worker: finds tasks whose reminder time or deadline has come and hands each to
 * {@link SlaTaskProcessor}. It is a system use case — there is no request, no user and no ambient tenant; each task
 * is processed within the tenant it belongs to. A failure on one task is logged and does not stop the others (the
 * failed one is retried on the next run).
 */
@Service
public class ProcessSlaHandler {

    private static final Logger log = LoggerFactory.getLogger(ProcessSlaHandler.class);

    private final TaskRepository taskRepository;
    private final SlaTaskProcessor processor;

    public ProcessSlaHandler(TaskRepository taskRepository, SlaTaskProcessor processor) {
        this.taskRepository = taskRepository;
        this.processor = processor;
    }

    /** What one run did. */
    public record Summary(int reminded, int overdue, int escalated, int failed) {
    }

    public Summary run(Instant now, int batchSize) {
        int reminded = 0;
        int overdue = 0;
        int escalated = 0;
        int failed = 0;

        List<DueTaskRef> toRemind = taskRepository.findDueForReminder(now, batchSize);
        for (DueTaskRef ref : toRemind) {
            try {
                if (processor.remind(ref.tenantId(), ref.taskId(), now)) {
                    reminded++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.error("Reminder failed for task {} (tenant {})", ref.taskId(), ref.tenantId().value(), e);
            }
        }

        List<DueTaskRef> overdueTasks = taskRepository.findOverdue(now, batchSize);
        for (DueTaskRef ref : overdueTasks) {
            try {
                SlaTaskProcessor.OverdueOutcome outcome = processor.handleOverdue(ref.tenantId(), ref.taskId(), now);
                if (outcome != SlaTaskProcessor.OverdueOutcome.NONE) {
                    overdue++;
                }
                if (outcome == SlaTaskProcessor.OverdueOutcome.ESCALATED) {
                    escalated++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.error("Overdue handling failed for task {} (tenant {})", ref.taskId(), ref.tenantId().value(), e);
            }
        }
        return new Summary(reminded, overdue, escalated, failed);
    }
}
