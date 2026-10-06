package com.flowpass.approval.domain.definition;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Domain rule: what a tenant-authored process definition may contain.
 *
 * <p>A BPMN definition is <em>code</em> that the process engine executes, and tenants author it. It is
 * therefore accepted only if it stays inside the platform's flow contract, enforced here as a whitelist:</p>
 * <ul>
 *   <li><b>No active content.</b> Only start/end events, user tasks, exclusive/parallel gateways and
 *       sequence flows (plus the multi-instance loop of a user task). No script/service/send/call tasks,
 *       sub-processes, listeners, extension elements, boundary events or DOCTYPE/entities (XXE).</li>
 *   <li><b>No code in expressions.</b> Conditions are limited to variables, literals and comparison /
 *       boolean / arithmetic operators — no property access, no method calls, no indexing, no assignment —
 *       so an expression can read process variables and nothing else.</li>
 *   <li><b>Engine attributes are restricted</b> to {@code assignee}, {@code collection} and
 *       {@code elementVariable} with the contract values ({@code ${approver}} over {@code approvers}).</li>
 *   <li><b>A runnable shape:</b> process id equals the template key, exactly one start event, every node
 *       reachable, no dead ends, and end events are {@code endApproved} / {@code endRejected} — the end
 *       event the run reaches is its outcome.</li>
 * </ul>
 * Pure Java (JDK XML parsing only) — no framework.
 */
public final class FlowDefinitionValidator {

    public static final int MAX_BYTES = 256 * 1024;
    public static final String END_APPROVED = "endApproved";
    public static final String END_REJECTED = "endRejected";

    private static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    private static final String BPMN_DI_NS = "http://www.omg.org/spec/BPMN/20100524/DI";
    private static final String FLOWABLE_NS = "http://flowable.org/bpmn";
    private static final String XSI_NS = "http://www.w3.org/2001/XMLSchema-instance";
    private static final String XMLNS_NS = "http://www.w3.org/2000/xmlns/";

    private static final Set<String> NODES = Set.of(
            "startEvent", "endEvent", "userTask", "exclusiveGateway", "parallelGateway");
    private static final Set<String> PROCESS_CHILDREN = Set.of(
            "startEvent", "endEvent", "userTask", "exclusiveGateway", "parallelGateway", "sequenceFlow",
            "documentation");
    private static final Set<String> CHILDREN_OF_USER_TASK = Set.of("multiInstanceLoopCharacteristics", "documentation");
    private static final Set<String> CHILDREN_OF_LOOP = Set.of("completionCondition");
    private static final Set<String> CHILDREN_OF_FLOW = Set.of("conditionExpression", "documentation");
    private static final Set<String> END_EVENT_IDS = Set.of(END_APPROVED, END_REJECTED);
    private static final Set<String> TWO_CHAR_OPERATORS = Set.of("==", "!=", "<=", ">=", "&&", "||");
    private static final Pattern LITERAL_ASSIGNEE = Pattern.compile("[A-Z][A-Z_]*:[A-Za-z0-9_.@-]{1,128}");

    private FlowDefinitionValidator() {
    }

    /** Throw {@link IllegalArgumentException} listing every problem when the definition is not acceptable. */
    public static void validate(String templateKey, String bpmnXml) {
        List<String> problems = problems(templateKey, bpmnXml);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Invalid flow definition: " + String.join("; ", problems));
        }
    }

    /** All problems found, empty when the definition is acceptable. */
    public static List<String> problems(String templateKey, String bpmnXml) {
        List<String> problems = new ArrayList<>();
        if (bpmnXml == null || bpmnXml.isBlank()) {
            return List.of("definition is empty");
        }
        if (bpmnXml.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return List.of("definition exceeds " + MAX_BYTES + " bytes");
        }
        Document document;
        try {
            document = parse(bpmnXml);
        } catch (Exception e) {
            return List.of("not well-formed XML (DOCTYPE and external entities are not allowed)");
        }
        Element root = document.getDocumentElement();
        if (!is(root, BPMN_NS, "definitions")) {
            return List.of("root element must be bpmn 'definitions'");
        }

        Element process = null;
        for (Element child : children(root)) {
            if (is(child, BPMN_NS, "process")) {
                if (process != null) {
                    problems.add("exactly one process is allowed");
                }
                process = child;
            } else if (!BPMN_DI_NS.equals(child.getNamespaceURI())
                    && !is(child, BPMN_NS, "documentation")) {
                problems.add("element '" + child.getLocalName() + "' is not allowed");
            }
        }
        if (process == null) {
            problems.add("a process is required");
            return problems;
        }
        checkProcess(templateKey, process, problems);
        return problems;
    }

    // ------------------------------------------------------------------------------------------------

    private static void checkProcess(String templateKey, Element process, List<String> problems) {
        if (!templateKey.equals(process.getAttribute("id"))) {
            problems.add("process id must equal the template key '" + templateKey + "'");
        }
        if ("false".equalsIgnoreCase(process.getAttribute("isExecutable"))) {
            problems.add("process must be executable");
        }
        checkAttributes(process, problems);

        Map<String, String> nodeTypes = new HashMap<>();
        List<Element> flows = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int starts = 0;
        for (Element element : children(process)) {
            String name = element.getLocalName();
            if (!BPMN_NS.equals(element.getNamespaceURI()) || !PROCESS_CHILDREN.contains(name)) {
                problems.add("element '" + name + "' is not allowed");
                continue;
            }
            checkElement(element, problems);
            String id = element.getAttribute("id");
            if (!"documentation".equals(name)) {
                if (id.isBlank()) {
                    problems.add("every " + name + " needs an id");
                } else if (!ids.add(id)) {
                    problems.add("duplicate id '" + id + "'");
                }
            }
            if ("sequenceFlow".equals(name)) {
                flows.add(element);
            } else if (NODES.contains(name)) {
                nodeTypes.put(id, name);
                if ("startEvent".equals(name)) {
                    starts++;
                }
            }
        }
        if (starts != 1) {
            problems.add("exactly one start event is required");
        }

        Set<String> endIds = new HashSet<>();
        nodeTypes.forEach((id, type) -> {
            if ("endEvent".equals(type)) {
                endIds.add(id);
                if (!END_EVENT_IDS.contains(id)) {
                    problems.add("end event '" + id + "' must be " + END_APPROVED + " or " + END_REJECTED);
                }
            }
        });
        if (!endIds.contains(END_APPROVED)) {
            problems.add("an end event '" + END_APPROVED + "' is required");
        }

        Map<String, List<String>> outgoing = new HashMap<>();
        for (Element flow : flows) {
            String source = flow.getAttribute("sourceRef");
            String target = flow.getAttribute("targetRef");
            if (!nodeTypes.containsKey(source) || !nodeTypes.containsKey(target)) {
                problems.add("flow '" + flow.getAttribute("id") + "' connects an unknown node");
                continue;
            }
            outgoing.computeIfAbsent(source, k -> new ArrayList<>()).add(target);
        }
        for (Map.Entry<String, String> node : nodeTypes.entrySet()) {
            if (!"endEvent".equals(node.getValue()) && !outgoing.containsKey(node.getKey())) {
                problems.add("'" + node.getKey() + "' has no outgoing flow");
            }
        }
        checkReachable(nodeTypes, outgoing, problems);
    }

    private static void checkReachable(Map<String, String> nodeTypes, Map<String, List<String>> outgoing,
                                       List<String> problems) {
        String start = nodeTypes.entrySet().stream()
                .filter(e -> "startEvent".equals(e.getValue())).map(Map.Entry::getKey).findFirst().orElse(null);
        if (start == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(List.of(start));
        while (!queue.isEmpty()) {
            String node = queue.poll();
            if (seen.add(node)) {
                queue.addAll(outgoing.getOrDefault(node, List.of()));
            }
        }
        for (String node : nodeTypes.keySet()) {
            if (!seen.contains(node)) {
                problems.add("'" + node + "' is not reachable from the start event");
            }
        }
    }

    /** Check one element (and its allowed descendants) for forbidden children, attributes and expressions. */
    private static void checkElement(Element element, List<String> problems) {
        checkAttributes(element, problems);
        Set<String> allowedChildren = switch (element.getLocalName()) {
            case "userTask" -> CHILDREN_OF_USER_TASK;
            case "multiInstanceLoopCharacteristics" -> CHILDREN_OF_LOOP;
            case "sequenceFlow" -> CHILDREN_OF_FLOW;
            default -> Set.of();
        };
        for (Element child : children(element)) {
            if (!BPMN_NS.equals(child.getNamespaceURI()) || !allowedChildren.contains(child.getLocalName())) {
                problems.add("element '" + child.getLocalName() + "' is not allowed inside "
                        + element.getLocalName());
                continue;
            }
            String name = child.getLocalName();
            if ("conditionExpression".equals(name) || "completionCondition".equals(name)) {
                checkAttributes(child, problems);
                String reason = unsafeExpression(child.getTextContent());
                if (reason != null) {
                    problems.add("expression '" + abbreviate(child.getTextContent()) + "' rejected: " + reason);
                }
            } else {
                checkElement(child, problems);
            }
        }
    }

    private static void checkAttributes(Element element, List<String> problems) {
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Attr attribute = (Attr) attributes.item(i);
            String ns = attribute.getNamespaceURI();
            String name = attribute.getLocalName() == null ? attribute.getName() : attribute.getLocalName();
            if (ns == null || XSI_NS.equals(ns) || XMLNS_NS.equals(ns)) {
                continue;
            }
            if (!FLOWABLE_NS.equals(ns)) {
                problems.add("attribute '" + attribute.getName() + "' is not allowed");
                continue;
            }
            String value = attribute.getValue();
            switch (name) {
                case "assignee" -> {
                    if (!"${approver}".equals(value) && !LITERAL_ASSIGNEE.matcher(value).matches()) {
                        problems.add("assignee must be ${approver} or a literal TYPE:id");
                    }
                }
                case "collection" -> {
                    if (!"approvers".equals(value)) {
                        problems.add("loop collection must be 'approvers'");
                    }
                }
                case "elementVariable" -> {
                    if (!"approver".equals(value)) {
                        problems.add("loop element variable must be 'approver'");
                    }
                }
                default -> problems.add("engine attribute '" + name + "' is not allowed");
            }
        }
    }

    // ------------------------------------------------------------------------------------------------

    /**
     * Why an expression is not allowed, or {@code null} when it is. Allowed: {@code ${...}} containing only
     * identifiers, numbers, quoted strings and the operators {@code == != < > <= >= && || ! + - * / % ( )}.
     * An identifier directly followed by {@code (} is a call and rejected; so are {@code .}, {@code [},
     * a single {@code =}, {@code ?}, {@code :}, {@code ;}, {@code #}, {@code $}, {@code @}, {@code ,}.
     */
    static String unsafeExpression(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (!text.startsWith("${") || !text.endsWith("}")) {
            return "must be of the form ${...}";
        }
        String body = text.substring(2, text.length() - 1);
        if (body.isBlank()) {
            return "is empty";
        }
        int i = 0;
        int n = body.length();
        while (i < n) {
            char c = body.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (Character.isDigit(c)) {
                i++;
                while (i < n && Character.isDigit(body.charAt(i))) {
                    i++;
                }
                if (i + 1 < n && body.charAt(i) == '.' && Character.isDigit(body.charAt(i + 1))) {
                    i++;
                    while (i < n && Character.isDigit(body.charAt(i))) {
                        i++;
                    }
                }
            } else if (c == '\'' || c == '"') {
                int end = body.indexOf(c, i + 1);
                if (end < 0) {
                    return "has an unterminated string";
                }
                if (body.substring(i + 1, end).indexOf('\\') >= 0) {
                    return "has an escape sequence in a string";
                }
                i = end + 1;
            } else if (Character.isLetter(c) || c == '_') {
                i++;
                while (i < n && (Character.isLetterOrDigit(body.charAt(i)) || body.charAt(i) == '_')) {
                    i++;
                }
                int next = i;
                while (next < n && Character.isWhitespace(body.charAt(next))) {
                    next++;
                }
                if (next < n && body.charAt(next) == '(') {
                    return "calls a function or method";
                }
            } else if (i + 1 < n && TWO_CHAR_OPERATORS.contains(body.substring(i, i + 2))) {
                i += 2;
            } else if ("<>!+-*/%()".indexOf(c) >= 0) {
                i++;
            } else {
                return "uses '" + c + "', which is not allowed";
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(null);
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    private static List<Element> children(Element parent) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i).getNodeType() == Node.ELEMENT_NODE) {
                result.add((Element) nodes.item(i));
            }
        }
        return result;
    }

    private static boolean is(Element element, String namespace, String localName) {
        return namespace.equals(element.getNamespaceURI()) && localName.equals(element.getLocalName());
    }

    private static String abbreviate(String text) {
        String trimmed = text == null ? "" : text.trim();
        return trimmed.length() <= 60 ? trimmed : trimmed.substring(0, 57) + "...";
    }
}
