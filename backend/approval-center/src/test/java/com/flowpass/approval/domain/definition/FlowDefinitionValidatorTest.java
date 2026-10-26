package com.flowpass.approval.domain.definition;

import com.flowpass.approval.support.TestBpmn;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The flow contract: what a tenant-authored process definition may contain. A definition is code the engine
 * runs, so most of these cases are hostile inputs that must be refused.
 */
class FlowDefinitionValidatorTest {

    private static final String KEY = "purchase_request";
    private final String valid = TestBpmn.purchaseRequest();

    private static List<String> problems(String xml) {
        return FlowDefinitionValidator.problems(KEY, xml);
    }

    private String mutate(String from, String to) {
        assertThat(valid).as("mutation anchor present").contains(from);
        return valid.replace(from, to);
    }

    // ---- accepted ------------------------------------------------------------------------------------

    @Test
    void theBuiltInDefinitionIsValid() {
        assertThat(problems(valid)).isEmpty();
        FlowDefinitionValidator.validate(KEY, valid);
    }

    @Test
    void aRenamedProcessIsValidForItsOwnKey() {
        assertThat(FlowDefinitionValidator.problems("expense_claim", TestBpmn.forKey("expense_claim"))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "${rejected}",
            "${hasApprovers}",
            "${amount >= 1000 && currency == 'MYR'}",
            "${amount < 100 || region != \"EU\"}",
            "${!rejected}",
            "${nrOfCompletedInstances == nrOfInstances}",
            "${(amount + fee) * 2 > 10.5}",
            "${ rejected }"})
    void ordinaryConditionsAreAccepted(String expression) {
        assertThat(FlowDefinitionValidator.unsafeExpression(expression)).isNull();
    }

    // ---- hostile expressions -------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "${execution.getVariable('x')}",
            "${runtimeService.deleteProcessInstance('a', 'b')}",
            "${T(java.lang.Runtime).getRuntime().exec('calc')}",
            "${a.b}",
            "${a['b']}",
            "${bean.method()}",
            "${method ()}",
            "${x = 1}",
            "${a ? b : c}",
            "${'unterminated}",
            "${'a\\'b'}",
            "${a; b}",
            "${#{inner}}",
            "${a, b}",
            "${@bean}",
            "${}",
            "#{rejected}",
            "rejected",
            "${rejected} && x",
            ""})
    void expressionsThatCouldDoMoreThanReadAVariableAreRefused(String expression) {
        assertThat(FlowDefinitionValidator.unsafeExpression(expression)).isNotNull();
    }

    @Test
    void aConditionThatCallsAMethodIsRejectedInTheDefinition() {
        List<String> found = problems(mutate("${hasApprovers}", "${execution.getVariable('x')}"));

        assertThat(found).anyMatch(p -> p.contains("rejected") && p.contains("execution.getVariable"));
    }

    // ---- hostile structure ---------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"scriptTask", "serviceTask", "sendTask", "callActivity", "subProcess", "boundaryEvent",
            "intermediateCatchEvent", "businessRuleTask", "eventSubProcess"})
    void activeContentElementsAreRefused(String element) {
        String xml = mutate("<endEvent id=\"endApproved\"", "<" + element + " id=\"evil\"/><endEvent id=\"endApproved\"");

        assertThat(problems(xml)).anyMatch(p -> p.contains("'" + element + "' is not allowed"));
    }

    @Test
    void scriptBodiesAreRefused() {
        String xml = mutate("<endEvent id=\"endApproved\"",
                "<scriptTask id=\"evil\" scriptFormat=\"groovy\"><script>Runtime.getRuntime().exec('x')</script></scriptTask>"
                        + "<endEvent id=\"endApproved\"");

        assertThat(problems(xml)).anyMatch(p -> p.contains("scriptTask"));
    }

    @Test
    void engineClassAttributesAreRefused() {
        String xml = mutate("<userTask id=\"manualReview\"", "<userTask id=\"manualReview\" flowable:class=\"x.Evil\"");

        assertThat(problems(xml)).anyMatch(p -> p.contains("engine attribute 'class' is not allowed"));
    }

    @Test
    void delegateExpressionAndExpressionAttributesAreRefused() {
        assertThat(problems(mutate("<userTask id=\"manualReview\"",
                "<userTask id=\"manualReview\" flowable:delegateExpression=\"${evil}\"")))
                .anyMatch(p -> p.contains("'delegateExpression'"));
        assertThat(problems(mutate("<userTask id=\"manualReview\"",
                "<userTask id=\"manualReview\" flowable:candidateGroups=\"admins\"")))
                .anyMatch(p -> p.contains("'candidateGroups'"));
    }

    @Test
    void attributesFromOtherEngineNamespacesAreRefused() {
        String xml = mutate("<userTask id=\"manualReview\"",
                "<userTask id=\"manualReview\" xmlns:a=\"http://activiti.org/bpmn\" a:class=\"x.Evil\"");

        assertThat(problems(xml)).anyMatch(p -> p.contains("attribute 'a:class' is not allowed"));
    }

    @Test
    void taskListenersInExtensionElementsAreRefused() {
        String xml = mutate("<userTask id=\"manualReview\" name=\"Manual review (no approver resolved)\"/>",
                "<userTask id=\"manualReview\"><extensionElements>"
                        + "<flowable:taskListener event=\"create\" class=\"x.Evil\"/></extensionElements></userTask>");

        assertThat(problems(xml)).anyMatch(p -> p.contains("extensionElements") && p.contains("not allowed inside userTask"));
    }

    @Test
    void anAssigneeThatIsNotTheApproverVariableOrALiteralIsRefused() {
        String xml = mutate("flowable:assignee=\"${approver}\"", "flowable:assignee=\"${runtimeService.x()}\"");

        assertThat(problems(xml)).anyMatch(p -> p.contains("assignee must be"));
    }

    @Test
    void aLiteralAssigneeIsAccepted() {
        String xml = mutate("<userTask id=\"manualReview\"", "<userTask id=\"manualReview\" flowable:assignee=\"USER:u_9\"");

        assertThat(problems(xml)).isEmpty();
    }

    @Test
    void theApproverLoopMustUseTheContractCollection() {
        assertThat(problems(mutate("flowable:collection=\"approvers\"", "flowable:collection=\"somethingElse\"")))
                .anyMatch(p -> p.contains("loop collection must be 'approvers'"));
        assertThat(problems(mutate("flowable:elementVariable=\"approver\"", "flowable:elementVariable=\"x\"")))
                .anyMatch(p -> p.contains("element variable must be 'approver'"));
    }

    @Test
    void doctypeAndExternalEntitiesAreRefused() {
        String xml = valid.replace("<definitions",
                "<!DOCTYPE definitions [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><definitions");

        assertThat(problems(xml)).singleElement().asString().contains("not well-formed XML");
    }

    @Test
    void notXmlAndEmptyAndOversizedAreRefused() {
        assertThat(problems("this is not xml")).singleElement().asString().contains("not well-formed");
        assertThat(problems("  ")).containsExactly("definition is empty");
        assertThat(problems(null)).containsExactly("definition is empty");
        assertThat(problems(valid + " ".repeat(FlowDefinitionValidator.MAX_BYTES)))
                .singleElement().asString().contains("exceeds");
    }

    @Test
    void aForeignRootElementIsRefused() {
        assertThat(problems("<html xmlns=\"http://www.w3.org/1999/xhtml\"/>"))
                .containsExactly("root element must be bpmn 'definitions'");
    }

    @Test
    void messageAndCollaborationElementsOutsideTheProcessAreRefused() {
        String xml = mutate("<process id=", "<message id=\"m\"/><process id=");

        assertThat(problems(xml)).anyMatch(p -> p.contains("'message' is not allowed"));
    }

    // ---- shape ---------------------------------------------------------------------------------------

    @Test
    void theProcessIdMustEqualTheTemplateKey() {
        assertThat(FlowDefinitionValidator.problems("another_key", valid))
                .anyMatch(p -> p.contains("process id must equal the template key 'another_key'"));
    }

    @Test
    void exactlyOneStartEventIsRequired() {
        String xml = mutate("<startEvent id=\"start\" name=\"Start\"/>",
                "<startEvent id=\"start\" name=\"Start\"/><startEvent id=\"start2\"/>");

        assertThat(problems(xml)).anyMatch(p -> p.contains("exactly one start event"));
    }

    @Test
    void endEventsMustBeTheOutcomeEndEventsAndApprovedIsRequired() {
        String xml = mutate("<endEvent id=\"endApproved\" name=\"Approved\"/>", "<endEvent id=\"endDone\"/>");
        xml = xml.replace("targetRef=\"endApproved\"", "targetRef=\"endDone\"");

        assertThat(problems(xml))
                .anyMatch(p -> p.contains("end event 'endDone' must be endApproved or endRejected"))
                .anyMatch(p -> p.contains("an end event 'endApproved' is required"));
    }

    @Test
    void unreachableNodesAndDeadEndsAreRefused() {
        String xml = mutate("<endEvent id=\"endApproved\"", "<userTask id=\"orphan\" name=\"x\"/><endEvent id=\"endApproved\"");

        assertThat(problems(xml))
                .anyMatch(p -> p.contains("'orphan' has no outgoing flow"))
                .anyMatch(p -> p.contains("'orphan' is not reachable"));
    }

    @Test
    void aFlowToAnUnknownNodeIsRefused() {
        String xml = mutate("targetRef=\"endRejected\"", "targetRef=\"nowhere\"");

        assertThat(problems(xml)).anyMatch(p -> p.contains("connects an unknown node"));
    }

    @Test
    void duplicateIdsAreRefused() {
        String xml = mutate("<endEvent id=\"endRejected\" name=\"Rejected\"/>",
                "<endEvent id=\"endRejected\" name=\"Rejected\"/><userTask id=\"endRejected\"/>");

        assertThat(problems(xml)).anyMatch(p -> p.contains("duplicate id 'endRejected'"));
    }

    @Test
    void aNonExecutableProcessIsRefused() {
        String xml = mutate("isExecutable=\"true\"", "isExecutable=\"false\"");

        assertThat(problems(xml)).anyMatch(p -> p.contains("must be executable"));
    }

    @Test
    void validateListsEveryProblemInOneException() {
        String xml = mutate("<userTask id=\"manualReview\"", "<userTask id=\"manualReview\" flowable:class=\"x.Evil\"")
                .replace("${hasApprovers}", "${a.b}");

        assertThatThrownBy(() -> FlowDefinitionValidator.validate(KEY, xml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid flow definition")
                .hasMessageContaining("'class'")
                .hasMessageContaining("a.b");
    }

    @Test
    void theApprovalRoundCollectionsOneToFiveAreAccepted() {
        assertThat(problems(mutate("flowable:collection=\"approvers2\"", "flowable:collection=\"approvers5\""))).isEmpty();
        assertThat(problems(mutate("flowable:collection=\"approvers\"", "flowable:collection=\"approvers3\""))).isEmpty();
    }

    @Test
    void aCollectionOutsideTheRoundContractIsRefused() {
        for (String bad : new String[] {"approvers1", "approvers6", "approvers0", "approvers22", "somethingElse", "approvers "}) {
            assertThat(problems(mutate("flowable:collection=\"approvers2\"", "flowable:collection=\"" + bad + "\"")))
                    .as(bad).anyMatch(p -> p.contains("loop collection must be"));
        }
    }
}
