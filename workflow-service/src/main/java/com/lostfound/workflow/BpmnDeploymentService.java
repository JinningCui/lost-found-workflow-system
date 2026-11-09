package com.lostfound.workflow;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Attr;

@Service
public class BpmnDeploymentService {
    private static final String FLOWABLE_NS = "http://flowable.org/bpmn";
    private static final Set<String> ELEMENTS = Set.of("definitions", "process", "startEvent", "endEvent", "serviceTask",
            "userTask", "exclusiveGateway", "sequenceFlow", "conditionExpression",
            "incoming", "outgoing", "documentation");
    private static final Set<String> CONDITIONS = Set.of("${approved}", "${!approved}", "${valuable}", "${!valuable}");
    private static final Set<String> KEYS = Set.of("claim_standard", "claim_dispute", "unclaimed_disposal");
    private static final Map<String, Set<String>> PROCESS_OPERATIONS = Map.of(
            "claim_standard", Set.of("verification.submitEvidence", "item.reserve", "claim.approve", "claim.reject",
                    "handover.issueCode", "item.finish", "claim.complete"),
            "claim_dispute", Set.of("item.freeze", "handover.revokeCode", "claim.disputed", "item.resolveWinner",
                    "item.reopen", "claim.rejectOthers", "claim.approve", "handover.issueCode", "item.finish", "claim.complete"),
            "unclaimed_disposal", Set.of("item.checkDisposalEligibility", "handover.transfer", "item.dispose"));
    private final RepositoryService repository;
    private final DeploymentAuditRepository audit;

    public BpmnDeploymentService(RepositoryService repository, DeploymentAuditRepository audit) {
        this.repository = repository;
        this.audit = audit;
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ProcessDefinition definition : repository.createProcessDefinitionQuery().orderByProcessDefinitionKey().asc()
                .orderByProcessDefinitionVersion().desc().list()) {
            DeploymentAudit record = audit.findById(definition.getDeploymentId()).orElse(null);
            rows.add(Map.of("id", definition.getId(), "key", definition.getKey(), "name", definition.getName() == null ? definition.getKey() : definition.getName(),
                    "version", definition.getVersion(), "deploymentId", definition.getDeploymentId(),
                    "deployedBy", record == null ? "system" : record.deployedBy,
                    "deployedAt", record == null ? "startup" : record.deployedAt.toString()));
        }
        return rows;
    }

    public Map<String, Object> upload(String fileName, byte[] bytes, String actor) {
        if (bytes.length == 0 || bytes.length > 1024 * 1024) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "BPMN 文件大小应在 1B 到 1MB 之间");
        String key = validate(bytes);
        String cleanName = fileName == null ? "workflow.bpmn20.xml" : fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (!cleanName.endsWith(".bpmn20.xml")) cleanName += ".bpmn20.xml";
        Deployment deployment = repository.createDeployment().name(cleanName)
                .addString(cleanName, new String(bytes, StandardCharsets.UTF_8)).deploy();
        audit.save(new DeploymentAudit(deployment.getId(), key, actor, cleanName));
        ProcessDefinition definition = repository.createProcessDefinitionQuery().deploymentId(deployment.getId()).singleResult();
        return Map.of("id", definition.getId(), "key", definition.getKey(), "name", definition.getName(),
                "version", definition.getVersion(), "deploymentId", deployment.getId(), "deployedBy", actor);
    }

    public Map<String, Object> deployDemoV2(String actor) {
        try {
            ClassPathResource resource = new ClassPathResource("demo-v2/claim-standard-v2.bpmn20.xml");
            return upload("claim-standard-v2.bpmn20.xml", resource.getContentAsByteArray(), actor);
        } catch (Exception ex) {
            throw new IllegalStateException("内置 v2 流程读取失败", ex);
        }
    }

    private String validate(byte[] bytes) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
            if (!"definitions".equals(doc.getDocumentElement().getLocalName())) throw new IllegalArgumentException("文件不是 BPMN definitions");
            NodeList all = doc.getElementsByTagName("*");
            Set<String> ids = new HashSet<>();
            Set<String> userTasks = new HashSet<>();
            Set<String> operations = new HashSet<>();
            Map<String, List<String>> operationNodes = new HashMap<>();
            Map<String, List<FlowEdge>> graph = new HashMap<>();
            String key = null;
            String startId = null;
            int processCount = 0;
            int startCount = 0;
            int endCount = 0;
            for (int i = 0; i < all.getLength(); i++) {
                Element el = (Element) all.item(i);
                String name = el.getLocalName();
                if (!ELEMENTS.contains(name)) throw new IllegalArgumentException("不允许的 BPMN 元素: " + name);
                validateAttributes(el);
                String id = el.getAttribute("id");
                if (!id.isBlank() && !ids.add(id)) throw new IllegalArgumentException("BPMN 元素 ID 重复: " + id);
                if ("process".equals(name)) {
                    processCount++;
                    key = id;
                    if (!KEYS.contains(key)) throw new IllegalArgumentException("仅允许更新三类已审核业务流程");
                    if (!"true".equals(el.getAttribute("isExecutable"))) throw new IllegalArgumentException("流程必须可执行");
                }
                if ("startEvent".equals(name)) { startCount++; startId = id; }
                if ("endEvent".equals(name)) endCount++;
                if ("serviceTask".equals(name)) {
                    String operation = validateServiceTask(el);
                    operations.add(operation);
                    operationNodes.computeIfAbsent(operation, unused -> new ArrayList<>()).add(id);
                }
                if ("userTask".equals(name)) {
                    userTasks.add(id);
                    if (el.hasAttributeNS(FLOWABLE_NS, "assignee") || el.hasAttributeNS(FLOWABLE_NS, "candidateUsers"))
                        throw new IllegalArgumentException("不允许在流程文件中指定任意处理人");
                    if (!Set.of("initialReview", "secondReview", "confirmPickup", "disputeReview", "disputePickup", "disposalReview").contains(id))
                        throw new IllegalArgumentException("未知人工任务: " + id);
                }
                if ("conditionExpression".equals(name) && !CONDITIONS.contains(el.getTextContent().trim()))
                    throw new IllegalArgumentException("不允许的条件表达式");
                if ("sequenceFlow".equals(name)) {
                    String condition = "";
                    NodeList conditions = el.getElementsByTagNameNS("http://www.omg.org/spec/BPMN/20100524/MODEL", "conditionExpression");
                    if (conditions.getLength() > 0) condition = conditions.item(0).getTextContent().trim();
                    graph.computeIfAbsent(el.getAttribute("sourceRef"), unused -> new ArrayList<>())
                            .add(new FlowEdge(el.getAttribute("targetRef"), condition));
                }
                if (el.hasAttributeNS(FLOWABLE_NS, "class") || (!"serviceTask".equals(name) && el.hasAttributeNS(FLOWABLE_NS, "expression")) ||
                        el.hasAttributeNS(FLOWABLE_NS, "resultVariable") || el.hasAttributeNS(FLOWABLE_NS, "calledElement"))
                    throw new IllegalArgumentException("流程文件包含未经允许的执行属性");
            }
            if (processCount != 1 || startCount != 1 || endCount < 1 || startId == null || startId.isBlank())
                throw new IllegalArgumentException("流程必须恰好包含一个 process 和 startEvent，且至少有一个 endEvent");
            if (!PROCESS_OPERATIONS.get(key).containsAll(operations)) throw new IllegalArgumentException("流程含有不属于此业务的服务操作");
            validateRequiredSteps(key, userTasks, operations, operationNodes, graph, startId);
            return key;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("BPMN 文件解析失败: " + ex.getMessage(), ex);
        }
    }

    private String validateServiceTask(Element el) {
        String expression = el.getAttributeNS(FLOWABLE_NS, "expression");
        String operation = ServiceInvoker.EXECUTABLE_OPERATIONS.stream()
                .filter(op -> expression.equals("${serviceInvoker.invoke(execution, '" + op + "')}"))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("服务任务只能调用受控 serviceInvoker 操作"));
        if (el.hasAttributeNS(FLOWABLE_NS, "delegateExpression")) throw new IllegalArgumentException("禁止委托表达式字段注入");
        return operation;
    }

    private void validateAttributes(Element el) {
        String element = el.getLocalName();
        for (int i = 0; i < el.getAttributes().getLength(); i++) {
            Attr attr = (Attr) el.getAttributes().item(i);
            if ("http://www.w3.org/2000/xmlns/".equals(attr.getNamespaceURI())) continue;
            String name = attr.getLocalName();
            String ns = attr.getNamespaceURI();
            if (FLOWABLE_NS.equals(ns)) {
                if ("serviceTask".equals(element) && "expression".equals(name)) continue;
                if ("userTask".equals(element) && "candidateGroups".equals(name)) {
                    String role = switch (el.getAttribute("id")) {
                        case "confirmPickup", "disputePickup" -> "STAFF";
                        default -> "ADMIN";
                    };
                    if (role.equals(attr.getValue())) continue;
                }
                throw new IllegalArgumentException("不允许的 Flowable 属性: " + name);
            }
            if ("http://www.w3.org/2001/XMLSchema-instance".equals(ns) && "conditionExpression".equals(element)
                    && "type".equals(name) && "tFormalExpression".equals(attr.getValue())) continue;
            if (ns != null && !ns.isBlank()) throw new IllegalArgumentException("不允许的属性命名空间");
            Set<String> permitted = switch (element) {
                case "definitions" -> Set.of("targetNamespace");
                case "process" -> Set.of("id", "name", "isExecutable");
                case "sequenceFlow" -> Set.of("id", "name", "sourceRef", "targetRef");
                case "startEvent", "endEvent", "exclusiveGateway", "serviceTask", "userTask" -> Set.of("id", "name");
                default -> Set.of();
            };
            if (!permitted.contains(name)) throw new IllegalArgumentException("不允许的 BPMN 属性: " + name);
        }
    }

    private void validateRequiredSteps(String key, Set<String> users, Set<String> operations,
                                       Map<String, List<String>> nodes, Map<String, List<FlowEdge>> graph, String startId) {
        if ("claim_standard".equals(key)) {
            if (!users.containsAll(Set.of("initialReview", "confirmPickup")) ||
                    !operations.containsAll(Set.of("verification.submitEvidence", "item.reserve", "claim.approve",
                            "claim.reject", "handover.issueCode", "item.finish", "claim.complete")))
                throw new IllegalArgumentException("普通认领缺少核验、审核或交接步骤");
            String verify = onlyNode(nodes, "verification.submitEvidence");
            String reserve = onlyNode(nodes, "item.reserve");
            String approve = onlyNode(nodes, "claim.approve");
            String issue = onlyNode(nodes, "handover.issueCode");
            String finish = onlyNode(nodes, "item.finish");
            String complete = onlyNode(nodes, "claim.complete");
            dominates(graph, startId, verify, "initialReview", null);
            dominates(graph, startId, "initialReview", reserve, null);
            dominates(graph, startId, reserve, approve, null);
            dominates(graph, startId, approve, issue, null);
            dominates(graph, startId, issue, "confirmPickup", null);
            dominates(graph, startId, "confirmPickup", finish, null);
            dominates(graph, startId, finish, complete, null);
            if (users.contains("secondReview")) dominates(graph, startId, "secondReview", reserve, Boolean.TRUE);
        } else if ("claim_dispute".equals(key)) {
            if (!users.containsAll(Set.of("disputeReview", "disputePickup")) ||
                    !operations.containsAll(Set.of("item.freeze", "handover.revokeCode", "claim.disputed",
                            "item.resolveWinner", "claim.rejectOthers", "item.reopen", "claim.approve",
                            "handover.issueCode", "item.finish", "claim.complete")))
                throw new IllegalArgumentException("争议流程缺少冻结、裁决或交接步骤");
            String freeze = onlyNode(nodes, "item.freeze");
            String revoke = onlyNode(nodes, "handover.revokeCode");
            String resolve = onlyNode(nodes, "item.resolveWinner");
            String approve = onlyNode(nodes, "claim.approve");
            String issue = onlyNode(nodes, "handover.issueCode");
            String finish = onlyNode(nodes, "item.finish");
            String complete = onlyNode(nodes, "claim.complete");
            dominates(graph, startId, freeze, revoke, null);
            dominates(graph, startId, revoke, "disputeReview", null);
            dominates(graph, startId, "disputeReview", resolve, null);
            dominates(graph, startId, resolve, approve, null);
            dominates(graph, startId, approve, issue, null);
            dominates(graph, startId, issue, "disputePickup", null);
            dominates(graph, startId, "disputePickup", finish, null);
            dominates(graph, startId, finish, complete, null);
        } else {
            if (!users.contains("disposalReview") ||
                    !operations.containsAll(Set.of("item.checkDisposalEligibility", "handover.transfer", "item.dispose")))
                throw new IllegalArgumentException("处置流程缺少到期核验、审核或移交步骤");
            String check = onlyNode(nodes, "item.checkDisposalEligibility");
            String transfer = onlyNode(nodes, "handover.transfer");
            String dispose = onlyNode(nodes, "item.dispose");
            dominates(graph, startId, check, "disposalReview", null);
            dominates(graph, startId, "disposalReview", transfer, null);
            dominates(graph, startId, transfer, dispose, null);
        }
    }

    private String onlyNode(Map<String, List<String>> nodes, String operation) {
        List<String> found = nodes.getOrDefault(operation, List.of());
        if (found.size() != 1) throw new IllegalArgumentException("必要服务操作必须恰好出现一次: " + operation);
        return found.get(0);
    }

    private void dominates(Map<String, List<FlowEdge>> graph, String startId, String required, String target, Boolean valuable) {
        if (!reachableWithout(graph, startId, target, null, valuable, new HashSet<>()))
            throw new IllegalArgumentException("必要流程节点不可达: " + target);
        if (reachableWithout(graph, startId, target, required, valuable, new HashSet<>()))
            throw new IllegalArgumentException("流程存在绕过必要审核或交接步骤的路径: " + required);
    }

    private boolean reachableWithout(Map<String, List<FlowEdge>> graph, String current, String target, String forbidden,
                                     Boolean valuable, Set<String> visited) {
        if (current.equals(forbidden) || !visited.add(current)) return false;
        if (current.equals(target)) return true;
        for (FlowEdge edge : graph.getOrDefault(current, List.of())) {
            if (valuable != null && ((valuable && "${!valuable}".equals(edge.condition())) ||
                    (!valuable && "${valuable}".equals(edge.condition())))) continue;
            if (reachableWithout(graph, edge.target(), target, forbidden, valuable, visited)) return true;
        }
        return false;
    }

    private record FlowEdge(String target, String condition) {}
}
