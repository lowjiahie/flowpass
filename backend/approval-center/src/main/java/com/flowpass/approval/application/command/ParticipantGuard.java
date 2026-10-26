package com.flowpass.approval.application.command;

import com.flowpass.approval.application.common.BusinessErrorCode;
import com.flowpass.approval.application.common.BusinessException;
import com.flowpass.approval.domain.common.ApproverRef;
import com.flowpass.approval.domain.runtime.ApprovalInstance;
import com.flowpass.approval.domain.runtime.ApprovalStatus;
import com.flowpass.approval.domain.task.TaskRepository;
import org.springframework.stereotype.Component;

/**
 * Who may be brought into a running approval by a transfer or an add-sign.
 *
 * <p>The same separation-of-duties rules that apply when approvers are first resolved: the request must still be
 * running, the initiator never approves their own request, and nobody is asked twice — a user who already holds,
 * or held, a task of the instance is not eligible.</p>
 */
@Component
public class ParticipantGuard {

    private static final int MAX_USER_ID_LENGTH = 128;

    private final TaskRepository taskRepository;

    public ParticipantGuard(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    /**
     * @return the new participant as a {@code USER}
     * @throws BusinessException {@code ILLEGAL_STATE} when the instance is not running; {@code VALIDATION_FAILED}
     *         when the user is blank, the initiator, or already involved
     */
    public ApproverRef requireNewParticipant(ApprovalInstance instance, String userId) {
        if (instance.status() != ApprovalStatus.RUNNING) {
            throw new BusinessException(BusinessErrorCode.ILLEGAL_STATE,
                    "The request is " + instance.status() + "; nobody can be added to it");
        }
        String user = userId == null ? "" : userId.trim();
        if (user.isEmpty() || user.length() > MAX_USER_ID_LENGTH) {
            throw new BusinessException(BusinessErrorCode.VALIDATION_FAILED, "A valid user is required");
        }
        ApproverRef target = new ApproverRef("USER", user);
        if (instance.isInitiatedBy(target)) {
            throw new BusinessException(BusinessErrorCode.VALIDATION_FAILED,
                    "The initiator cannot approve their own request");
        }
        if (taskRepository.existsUserTask(instance.tenantId(), instance.id().value(), user)) {
            throw new BusinessException(BusinessErrorCode.VALIDATION_FAILED,
                    user + " is already involved in this request");
        }
        return target;
    }
}
