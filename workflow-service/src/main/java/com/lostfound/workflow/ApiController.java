package com.lostfound.workflow;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.engine.TaskService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final AuthService auth;
    private final InternalClient client;
    private final CatalogService catalog;
    private final RuntimeService runtime;
    private final org.flowable.engine.TaskService tasks;
    private final HistoryService history;
    private final BpmnDeploymentService deployments;

    public ApiController(AuthService auth, InternalClient client, CatalogService catalog, RuntimeService runtime,
                         org.flowable.engine.TaskService tasks, HistoryService history, BpmnDeploymentService deployments) {
        this.auth = auth;
        this.client = client;
        this.catalog = catalog;
        this.runtime = runtime;
        this.tasks = tasks;
        this.history = history;
        this.deployments = deployments;
    }

    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> input) {
        return auth.login(string(input, "username"), string(input, "password"));
    }

    @GetMapping("/auth/me")
    public AuthService.User me(HttpServletRequest request) { return auth.current(request); }

    @GetMapping("/items")
    public Object items(@RequestParam(required = false) String query, @RequestParam(required = false) String category,
                        @RequestParam(required = false) String status) {
        StringBuilder path = new StringBuilder("/items?");
        if (query != null && !query.isBlank()) path.append("query=").append(encode(query)).append('&');
        if (category != null && !category.isBlank()) path.append("category=").append(encode(category)).append('&');
        if (status != null && !status.isBlank()) path.append("status=").append(encode(status));
        return client.get("item-service", "item.read", path.toString());
    }

    @GetMapping("/items/{id}")
    public Map<String, Object> item(@PathVariable String id) {
        Map<String, Object> result = new LinkedHashMap<>(InternalClient.object(client.get("item-service", "item.read", "/items/" + encode(id))));
        Object questions = client.get("verification-service", "verification.readQuestions", "/internal/clues/" + encode(id) + "/questions");
        result.put("verificationQuestions", questions instanceof List<?> ? questions : List.of());
        return result;
    }

    @PostMapping("/items")
    public Map<String, Object> createItem(@RequestBody Map<String, Object> input, HttpServletRequest request) {
        AuthService.User actor = auth.require(request, "ADMIN", "STAFF");
        if (string(input, "title") == null || string(input, "title").isBlank()) throw new IllegalArgumentException("物品名称不能为空");
        Map<String, Object> itemBody = new HashMap<>(input);
        Object clueInput = itemBody.remove("hiddenClues");
        Map<String, Object> clues = normalizeClues(clueInput);
        if (clues.isEmpty()) throw new IllegalArgumentException("至少需要一条隐藏线索");
        itemBody.put("createdBy", actor.id());
        itemBody.put("operationId", UUID.randomUUID().toString());
        Map<String, Object> result = InternalClient.object(client.post("item-service", "item.create", "/internal/items", itemBody));
        String id = string(result, "id");
        if (id == null) id = string(result, "itemId");
        if (id == null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "物品服务未返回 ID");
        client.post("verification-service", "verification.storeClues", "/internal/clues",
                body("itemId", id, "clues", clues, "operationId", "clues:" + id));
        return result;
    }

    @PostMapping("/claims")
    public Map<String, Object> submitClaim(@RequestBody Map<String, Object> input, HttpServletRequest request) {
        AuthService.User user = auth.require(request, "STUDENT", "ADMIN");
        String itemId = required(input, "itemId");
        Map<String, Object> item = InternalClient.object(client.get("item-service", "item.read", "/internal/items/" + encode(itemId)));
        String itemStatus = string(item, "status");
        if (!Set.of("PUBLISHED", "RESERVED").contains(itemStatus)) throw new ResponseStatusException(HttpStatus.CONFLICT, "当前物品不可认领");
        String claimId = UUID.randomUUID().toString();
        client.post("claim-service", "claim.submit", "/internal/claims",
                body("id", claimId, "itemId", itemId, "claimantId", user.id(),
                        "description", required(input, "description"), "operationId", "submit:" + claimId));
        Map<String, Object> vars = body("itemId", itemId, "claimId", claimId, "claimantId", user.id(),
                "valuable", Boolean.TRUE.equals(item.get("valuable")) || Boolean.TRUE.equals(item.get("highValue")),
                "answers", input.getOrDefault("answers", Map.of()));
        ProcessInstance process = runtime.startProcessInstanceByKey("claim_standard", claimId, vars);
        Map<String, Object> latestItem = InternalClient.object(client.get("item-service", "item.read", "/internal/items/" + encode(itemId)));
        if ("RESERVED".equals(string(latestItem, "status")) && Boolean.TRUE.equals(runtime.getVariable(process.getId(), "preliminaryPassed"))) {
            List<String> competing = activeClaimIds(itemId);
            if (competing.size() >= 2) {
                ProcessInstance dispute = startDisputeInternal(itemId, competing, "system", "第二份申请初步核验通过");
                return Map.of("claimId", claimId, "processInstanceId", dispute.getId(), "status", "DISPUTED");
            }
        }
        return Map.of("claimId", claimId, "processInstanceId", process.getId(), "status", "SUBMITTED");
    }

    @GetMapping("/claims")
    public Object claims(@RequestParam(required = false) String itemId, HttpServletRequest request) {
        AuthService.User user = auth.current(request);
        StringBuilder path = new StringBuilder("/internal/claims?");
        if (itemId != null && !itemId.isBlank()) path.append("itemId=").append(encode(itemId)).append('&');
        if ("STUDENT".equals(user.role())) path.append("claimantId=").append(encode(user.id()));
        return client.get("claim-service", "claim.read", path.toString());
    }

    @GetMapping("/claims/{id}")
    public Map<String, Object> claim(@PathVariable String id, HttpServletRequest request) {
        AuthService.User user = auth.current(request);
        Map<String, Object> claim = InternalClient.object(client.get("claim-service", "claim.read", "/internal/claims/" + encode(id)));
        if ("STUDENT".equals(user.role()) && !user.id().equals(string(claim, "claimantId")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能查看自己的申请");
        return claim;
    }

    @GetMapping("/admin/claims/{claimId}/evidence")
    public Map<String, Object> reviewEvidence(@PathVariable String claimId, HttpServletRequest request) {
        auth.require(request, "ADMIN");
        Map<String, Object> claim = InternalClient.object(client.get("claim-service", "claim.read", "/internal/claims/" + encode(claimId)));
        String itemId = required(claim, "itemId");
        Object evidence = client.get("verification-service", "verification.readEvidence", "/internal/evidence/" + encode(claimId));
        Object clues = client.get("verification-service", "verification.readClues", "/internal/clues/" + encode(itemId));
        return Map.of("claim", claim, "evidence", evidence, "clues", clues);
    }

    @GetMapping("/tasks")
    public List<Map<String, Object>> taskList(HttpServletRequest request) {
        AuthService.User user = auth.require(request, "ADMIN", "STAFF");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Task task : tasks.createTaskQuery().list()) {
            String key = task.getTaskDefinitionKey();
            if (isStaffTask(key) != "STAFF".equals(user.role())) continue;
            Map<String, Object> vars = new HashMap<>(runtime.getVariables(task.getProcessInstanceId()));
            vars.remove("answers");
            rows.add(Map.of("id", task.getId(), "name", task.getName(), "taskDefinitionKey", key,
                    "processInstanceId", task.getProcessInstanceId(), "variables", vars));
        }
        return rows;
    }

    @PostMapping("/tasks/{id}/complete")
    public Map<String, Object> completeTask(@PathVariable String id, @RequestBody Map<String, Object> input, HttpServletRequest request) {
        AuthService.User user = auth.require(request, "ADMIN");
        Task task = tasks.createTaskQuery().taskId(id).singleResult();
        if (task == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "待办任务不存在");
        String key = task.getTaskDefinitionKey();
        if (isStaffTask(key)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "交接任务请通过核码接口完成");
        String decision = required(input, "decision").toUpperCase();
        if (!Set.of("APPROVE", "REJECT").contains(decision)) throw new IllegalArgumentException("decision 必须是 APPROVE 或 REJECT");
        boolean approved = "APPROVE".equals(decision);
        Map<String, Object> vars = new HashMap<>(runtime.getVariables(task.getProcessInstanceId()));
        if ("secondReview".equals(key) && user.id().equals(String.valueOf(vars.get("firstReviewerId"))))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "复核必须由另一位管理员执行");
        if ("disposalReview".equals(key) && user.id().equals(String.valueOf(vars.get("initiatorId"))))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "处置审核必须由另一位管理员执行");
        Map<String, Object> completeVars = body("approved", approved, "reviewerId", user.id(), "reviewReason", input.get("reason"));
        if ("initialReview".equals(key)) completeVars.put("firstReviewerId", user.id());
        if ("disputeReview".equals(key) && approved) {
            String winner = required(input, "winningClaimId");
            Object ids = vars.get("claimIds");
            if (!(ids instanceof List<?> list) || !list.contains(winner)) throw new IllegalArgumentException("获准申请不在争议申请中");
            completeVars.put("claimId", winner);
        }
        if ("disposalReview".equals(key)) completeVars.put("destination", required(input, "destination"));
        if (Set.of("initialReview", "secondReview", "disputeReview").contains(key)) {
            String claimId = "disputeReview".equals(key) && approved ? String.valueOf(completeVars.get("claimId")) : String.valueOf(vars.get("claimId"));
            client.post("verification-service", "verification.recordReview", "/internal/reviews",
                    body("claimId", claimId, "reviewerId", user.id(), "stage", switch (key) {
                        case "initialReview" -> "INITIAL";
                        case "secondReview" -> "SECOND";
                        default -> "DISPUTE";
                    }, "decision", decision, "reason", input.get("reason"), "operationId", "review:" + id));
        }
        if ("initialReview".equals(key) && approved) {
            String itemId = String.valueOf(vars.get("itemId"));
            Map<String, Object> item = InternalClient.object(client.get("item-service", "item.read", "/internal/items/" + encode(itemId)));
            if ("RESERVED".equals(string(item, "status"))) {
                List<String> competing = activeClaimIds(itemId);
                if (competing.size() >= 2) {
                    ProcessInstance dispute = startDisputeInternal(itemId, competing, user.id(), "管理员确认多份有效申请");
                    return Map.of("taskId", id, "completed", true, "decision", decision,
                            "processInstanceId", dispute.getId(), "status", "DISPUTED");
                }
            }
        }
        tasks.complete(id, completeVars);
        return Map.of("taskId", id, "completed", true, "decision", decision);
    }

    @PostMapping("/disputes")
    public Map<String, Object> startDispute(@RequestBody Map<String, Object> input, HttpServletRequest request) {
        AuthService.User user = auth.require(request, "ADMIN");
        String itemId = required(input, "itemId");
        List<String> claimIds = stringList(input.get("claimIds"));
        if (claimIds.size() < 2) throw new IllegalArgumentException("争议流程至少需要两份申请");
        for (String claimId : claimIds) {
            Map<String, Object> claim = InternalClient.object(client.get("claim-service", "claim.read", "/internal/claims/" + encode(claimId)));
            if (!itemId.equals(string(claim, "itemId"))) throw new IllegalArgumentException("申请不属于此物品");
        }
        ProcessInstance process = startDisputeInternal(itemId, claimIds, user.id(), string(input, "reason"));
        return Map.of("processInstanceId", process.getId(), "itemId", itemId, "status", "DISPUTED");
    }

    @PostMapping("/disposals")
    public Map<String, Object> startDisposal(@RequestBody Map<String, Object> input, HttpServletRequest request) {
        AuthService.User user = auth.require(request, "ADMIN");
        String itemId = required(input, "itemId");
        ProcessInstance process = runtime.startProcessInstanceByKey("unclaimed_disposal", itemId,
                body("itemId", itemId, "initiatorId", user.id(), "reason", input.get("reason"), "destination", input.get("destination")));
        return Map.of("processInstanceId", process.getId(), "itemId", itemId, "status", "PENDING_REVIEW");
    }

    @GetMapping("/pickup/{claimId}/code")
    public Map<String, Object> pickupCode(@PathVariable String claimId, HttpServletRequest request) {
        AuthService.User user = auth.current(request);
        if ("STAFF".equals(user.role())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "员工不能查看领取码，请由认领人出示");
        Map<String, Object> claim = InternalClient.object(client.get("claim-service", "claim.read", "/internal/claims/" + encode(claimId)));
        if ("STUDENT".equals(user.role()) && !user.id().equals(string(claim, "claimantId")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能查看自己的领取码");
        return InternalClient.object(client.get("handover-service", "handover.readCode", "/internal/codes?claimId=" + encode(claimId)));
    }

    @PostMapping("/pickup/{claimId}/redeem")
    public Map<String, Object> redeem(@PathVariable String claimId, @RequestBody Map<String, Object> input, HttpServletRequest request) {
        AuthService.User user = auth.require(request, "STAFF");
        Task pickupTask = null;
        for (Task task : tasks.createTaskQuery().list()) {
            if (isStaffTask(task.getTaskDefinitionKey()) && claimId.equals(String.valueOf(runtime.getVariable(task.getProcessInstanceId(), "claimId")))) {
                pickupTask = task;
                break;
            }
        }
        if (pickupTask == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "没有待交接任务");
        String itemId = String.valueOf(runtime.getVariable(pickupTask.getProcessInstanceId(), "itemId"));
        String code = required(input, "code");
        Map<String, Object> item = InternalClient.object(client.get("item-service", "item.read", "/internal/items/" + encode(itemId)));
        String currentStatus = string(item, "status");
        boolean transitionedNow = false;
        if ("RESERVED".equals(currentStatus)) {
            client.post("item-service", "item.finish", "/internal/items/" + encode(itemId) + "/transition",
                    body("from", "RESERVED", "to", "HANDING_OVER", "claimId", claimId,
                            "operationId", "begin-handover:" + UUID.randomUUID()));
            transitionedNow = true;
        } else if (!"HANDING_OVER".equals(currentStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "物品不在可交接状态");
        }
        try {
            client.post("handover-service", "handover.redeemCode", "/internal/codes/redeem",
                    body("itemId", itemId, "claimId", claimId, "code", code,
                            "staffId", user.id(), "operationId", "redeem:" + pickupTask.getId()));
        } catch (RuntimeException ex) {
            if (transitionedNow) {
                client.post("item-service", "item.finish", "/internal/items/" + encode(itemId) + "/transition",
                        body("from", "HANDING_OVER", "to", "RESERVED", "claimId", claimId,
                                "operationId", "rollback-handover:" + UUID.randomUUID()));
            }
            throw ex;
        }
        tasks.complete(pickupTask.getId(), Map.of("staffId", user.id()));
        return Map.of("claimId", claimId, "itemId", itemId, "status", "HANDED_OVER");
    }

    @GetMapping("/processes")
    public List<Map<String, Object>> processes(HttpServletRequest request) {
        auth.require(request, "ADMIN", "STAFF");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (HistoricProcessInstance process : history.createHistoricProcessInstanceQuery().orderByProcessInstanceStartTime().desc().listPage(0, 100)) {
            rows.add(body("id", process.getId(), "processDefinitionId", process.getProcessDefinitionId(),
                    "businessKey", process.getBusinessKey(), "startedAt", process.getStartTime(),
                    "endedAt", process.getEndTime(), "status", process.getEndTime() == null ? "RUNNING" : "COMPLETED"));
        }
        return rows;
    }

    @GetMapping("/admin/services")
    public Object services(HttpServletRequest request) { auth.require(request, "ADMIN"); return catalog.list(); }

    @PostMapping("/admin/services/register")
    public CatalogEntry registerService(@RequestBody Map<String, Object> input, HttpServletRequest request) {
        auth.require(request, "ADMIN");
        return catalog.register(required(input, "serviceName"));
    }

    @PatchMapping("/admin/services/{name}")
    public CatalogEntry updateService(@PathVariable String name, @RequestBody Map<String, Object> input, HttpServletRequest request) {
        auth.require(request, "ADMIN");
        return catalog.setEnabled(name, Boolean.TRUE.equals(input.get("enabled")));
    }

    @GetMapping("/admin/process-definitions")
    public Object definitions(HttpServletRequest request) { auth.require(request, "ADMIN"); return deployments.list(); }

    @PostMapping("/admin/process-definitions/upload")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file, HttpServletRequest request) throws java.io.IOException {
        AuthService.User user = auth.require(request, "ADMIN");
        return deployments.upload(file.getOriginalFilename(), file.getBytes(), user.id());
    }

    @PostMapping("/admin/process-definitions/demo-v2")
    public Map<String, Object> demoV2(HttpServletRequest request) {
        AuthService.User user = auth.require(request, "ADMIN");
        return deployments.deployDemoV2(user.id());
    }

    private static boolean isStaffTask(String key) { return "confirmPickup".equals(key) || "disputePickup".equals(key); }

    private ProcessInstance startDisputeInternal(String itemId, List<String> claimIds, String initiatorId, String reason) {
        Map<String, Object> vars = body("itemId", itemId, "claimIds", claimIds, "claimId", claimIds.get(0),
                "initiatorId", initiatorId, "reason", reason);
        ProcessInstance process = runtime.startProcessInstanceByKey("claim_dispute", itemId, vars);
        for (ProcessInstance active : runtime.createProcessInstanceQuery().processDefinitionKey("claim_standard")
                .variableValueEquals("itemId", itemId).list()) {
            runtime.deleteProcessInstance(active.getId(), "转入争议认领流程 " + process.getId());
        }
        return process;
    }

    private List<String> activeClaimIds(String itemId) {
        Object found = client.get("claim-service", "claim.read", "/internal/claims?itemId=" + encode(itemId));
        List<String> result = new ArrayList<>();
        if (found instanceof List<?> rows) for (Object row : rows) {
            Map<String, Object> claim = InternalClient.object(row);
            if (Set.of("SUBMITTED", "APPROVED", "DISPUTED").contains(string(claim, "status"))) result.add(required(claim, "id"));
        }
        return result;
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String string(Map<String, Object> map, String key) { Object value = map.get(key); return value == null ? null : String.valueOf(value); }
    private static String required(Map<String, Object> map, String key) {
        String value = string(map, key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少必填字段: " + key);
        return value;
    }
    private static Map<String, Object> body(Object... pairs) {
        Map<String, Object> result = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) if (pairs[i + 1] != null) result.put(String.valueOf(pairs[i]), pairs[i + 1]);
        return result;
    }
    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object part : list) if (part != null) result.add(String.valueOf(part));
        return result;
    }
    private static Map<String, Object> normalizeClues(Object input) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (input instanceof Map<?, ?> map) for (var entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        if (input instanceof List<?> list) for (Object row : list) {
            if (row instanceof Map<?, ?> map && map.get("question") != null && map.get("answer") != null)
                result.put(String.valueOf(map.get("question")), String.valueOf(map.get("answer")));
        }
        return result;
    }
}
