package com.lostfound.workflow;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** The only BPMN service-task delegate. Operation names are checked against the catalog. */
@Component("serviceInvoker")
public class ServiceInvoker {
    public static final Set<String> EXECUTABLE_OPERATIONS = Set.of(
            "verification.submitEvidence", "item.reserve", "item.freeze", "item.resolveWinner", "item.reopen",
            "item.finish", "item.dispose", "item.checkDisposalEligibility", "claim.approve", "claim.reject",
            "claim.complete", "claim.disputed", "claim.rejectOthers", "handover.issueCode", "handover.revokeCode",
            "handover.transfer");
    private final InternalClient client;

    public ServiceInvoker(InternalClient client) { this.client = client; }

    public void invoke(DelegateExecution execution, String operation) {
        if (!EXECUTABLE_OPERATIONS.contains(operation)) throw new IllegalArgumentException("流程操作不在受控表内: " + operation);
        String service = switch (operation.split("\\.")[0]) {
            case "item" -> "item-service";
            case "claim" -> "claim-service";
            case "verification" -> "verification-service";
            case "handover" -> "handover-service";
            default -> throw new IllegalArgumentException("未知流程操作: " + operation);
        };
        // Also verify local-only operations before they can inspect other services.
        clientCatalogCheck(service, operation);
        String itemId = str(execution, "itemId");
        String claimId = str(execution, "claimId");
        String opId = execution.getProcessInstanceId() + ":" + execution.getCurrentActivityId();
        switch (operation) {
            case "verification.submitEvidence" -> {
                Map<String, Object> evidence = InternalClient.object(client.post(service, operation, "/internal/evidence",
                        body("claimId", claimId, "itemId", itemId, "answers", execution.getVariable("answers"), "operationId", opId)));
                execution.setVariable("evidenceScore", evidence.get("score"));
                execution.setVariable("preliminaryPassed", evidence.get("preliminaryPassed"));
                execution.removeVariable("answers");
            }
            case "item.reserve" -> transition(operation, itemId, "PUBLISHED", "RESERVED", claimId, opId);
            case "item.freeze" -> {
                Map<String, Object> item = InternalClient.object(client.get("item-service", "item.read", "/internal/items/" + itemId));
                String from = InternalClient.string(item, "status");
                if (!Set.of("PUBLISHED", "RESERVED").contains(from)) throw new ResponseStatusException(HttpStatus.CONFLICT, "当前物品不能发起争议");
                transition(operation, itemId, from, "DISPUTED", claimId, opId);
            }
            case "item.resolveWinner" -> transition(operation, itemId, "DISPUTED", "RESERVED", claimId, opId);
            case "item.reopen" -> transition(operation, itemId, "DISPUTED", "PUBLISHED", null, opId);
            case "item.finish" -> transition(operation, itemId, "HANDING_OVER", "HANDED_OVER", claimId, opId);
            case "item.dispose" -> transition(operation, itemId, "PUBLISHED", "DISPOSED", null, opId);
            case "item.checkDisposalEligibility" -> checkDisposal(itemId);
            case "claim.approve" -> status(operation, claimId, "APPROVED", opId);
            case "claim.reject" -> status(operation, claimId, "REJECTED", opId);
            case "claim.complete" -> status(operation, claimId, "COMPLETED", opId);
            case "claim.disputed" -> {
                for (String id : claimIds(execution)) status(operation, id, "DISPUTED", opId + ":" + id);
            }
            case "claim.rejectOthers" -> {
                boolean approved = Boolean.TRUE.equals(execution.getVariable("approved"));
                for (String id : claimIds(execution)) {
                    if (!approved || !id.equals(claimId)) status(operation, id, "REJECTED", opId + ":" + id);
                }
            }
            case "handover.issueCode" -> client.post(service, operation, "/internal/codes",
                    body("itemId", itemId, "claimId", claimId, "operationId", opId));
            case "handover.revokeCode" -> {
                for (String id : claimIds(execution)) {
                    try {
                        client.post(service, operation, "/internal/codes/revoke",
                                body("itemId", itemId, "claimId", id, "operationId", opId + ":" + id));
                    } catch (ResponseStatusException ex) {
                        // A competing application may never have received a pickup code.
                        if (ex.getStatusCode() != HttpStatus.NOT_FOUND) throw ex;
                    }
                }
            }
            case "handover.transfer" -> client.post(service, operation, "/internal/transfers",
                    body("itemId", itemId, "destination", execution.getVariable("destination"),
                            "operatorId", execution.getVariable("reviewerId"), "operationId", opId));
            default -> throw new IllegalArgumentException("流程操作未实现: " + operation);
        }
    }

    private void clientCatalogCheck(String service, String operation) {
        // InternalClient validates all remote calls. Local checks still need an enabled service.
        if (!CatalogService.OPERATIONS.getOrDefault(service, Set.of()).contains(operation)) {
            throw new IllegalArgumentException("服务操作不在白名单: " + operation);
        }
    }

    private void checkDisposal(String itemId) {
        Map<String, Object> item = InternalClient.object(client.get("item-service", "item.read", "/internal/items/" + itemId));
        if (!"PUBLISHED".equals(InternalClient.string(item, "status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有公开待认领物品可处置");
        }
        String foundAt = InternalClient.string(item, "foundAt");
        if (foundAt == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "缺少拾得时间");
        Instant found;
        try { found = Instant.parse(foundAt); }
        catch (Exception ex) {
            try { found = OffsetDateTime.parse(foundAt).toInstant(); }
            catch (Exception ex2) { found = LocalDate.parse(foundAt.substring(0, 10)).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant(); }
        }
        if (found.isAfter(Instant.now().minus(30, ChronoUnit.DAYS))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "未达到 30 天保管期");
        }
        Object result = client.get("claim-service", "claim.read", "/internal/claims?itemId=" + encode(itemId));
        if (result instanceof List<?> list) {
            for (Object row : list) {
                Map<String, Object> claim = InternalClient.object(row);
                String status = InternalClient.string(claim, "status");
                if (Set.of("SUBMITTED", "APPROVED", "DISPUTED").contains(status)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "仍有有效认领申请");
                }
            }
        }
    }

    private void transition(String operation, String itemId, String from, String to, String claimId, String opId) {
        client.post("item-service", operation, "/internal/items/" + itemId + "/transition",
                body("from", from, "to", to, "claimId", claimId, "operationId", opId));
    }

    private void status(String operation, String claimId, String status, String opId) {
        client.post("claim-service", operation, "/internal/claims/" + claimId + "/status",
                body("status", status, "operationId", opId));
    }

    private static Map<String, Object> body(Object... pairs) {
        Map<String, Object> body = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) if (pairs[i + 1] != null) body.put((String) pairs[i], pairs[i + 1]);
        return body;
    }

    private static String str(DelegateExecution execution, String name) {
        Object value = execution.getVariable(name);
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> claimIds(DelegateExecution execution) {
        Object value = execution.getVariable("claimIds");
        List<String> ids = new ArrayList<>();
        if (value instanceof List<?> list) for (Object id : list) ids.add(String.valueOf(id));
        return ids;
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
