package com.lostfound.workflow;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CatalogService {
    public static final Map<String, Set<String>> OPERATIONS = Map.of(
            "item-service", Set.of("item.create", "item.read", "item.reserve", "item.freeze", "item.resolveWinner", "item.reopen", "item.finish", "item.dispose", "item.checkDisposalEligibility"),
            "claim-service", Set.of("claim.submit", "claim.read", "claim.approve", "claim.reject", "claim.disputed", "claim.complete", "claim.rejectOthers"),
            "verification-service", Set.of("verification.storeClues", "verification.readQuestions", "verification.readClues", "verification.readEvidence", "verification.submitEvidence", "verification.recordReview"),
            "handover-service", Set.of("handover.issueCode", "handover.readCode", "handover.revokeCode", "handover.redeemCode", "handover.transfer"));
    private final CatalogRepository repository;
    private final DiscoveryClient discovery;

    public CatalogService(CatalogRepository repository, DiscoveryClient discovery) {
        this.repository = repository;
        this.discovery = discovery;
    }

    @PostConstruct
    void seed() {
        for (String name : OPERATIONS.keySet()) {
            if (!repository.existsById(name)) repository.save(new CatalogEntry(name, true));
        }
    }

    public void requireEnabled(String serviceName, String operation) {
        if (!OPERATIONS.getOrDefault(serviceName, Set.of()).contains(operation)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "操作不在服务白名单内: " + operation);
        }
        CatalogEntry entry = repository.findById(serviceName).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "服务未纳管: " + serviceName));
        if (!entry.enabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "服务已暂停: " + serviceName);
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String name : OPERATIONS.keySet()) {
            CatalogEntry entry = repository.findById(name).orElse(null);
            List<ServiceInstance> instances = discovery.getInstances(name);
            boolean healthy = false;
            for (ServiceInstance instance : instances) {
                try {
                    RestClient.create().get().uri(instance.getUri().resolve("/actuator/health")).retrieve().toBodilessEntity();
                    healthy = true;
                    break;
                } catch (Exception ignored) {
                    // All instances are inspected; unavailable ones remain visible in the response.
                }
            }
            result.add(Map.of("serviceName", name, "enabled", entry != null && entry.enabled,
                    "registered", entry != null, "healthy", healthy, "instances", instances.size(),
                    "operations", OPERATIONS.get(name)));
        }
        return result;
    }

    public CatalogEntry register(String serviceName) {
        if (!OPERATIONS.containsKey(serviceName)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知服务名");
        if (discovery.getInstances(serviceName).isEmpty()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "注册中心尚未发现该服务");
        CatalogEntry entry = repository.findById(serviceName).orElse(new CatalogEntry(serviceName, true));
        entry.enabled = true;
        entry.updatedAt = Instant.now();
        return repository.save(entry);
    }

    public CatalogEntry setEnabled(String serviceName, boolean enabled) {
        CatalogEntry entry = repository.findById(serviceName).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "服务未纳管"));
        entry.enabled = enabled;
        entry.updatedAt = Instant.now();
        return repository.save(entry);
    }

    public List<ServiceInstance> instances(String serviceName) {
        return discovery.getInstances(serviceName);
    }
}
