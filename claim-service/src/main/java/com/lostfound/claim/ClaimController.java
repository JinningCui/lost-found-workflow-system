package com.lostfound.claim;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class ClaimController {
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "SUBMITTED", Set.of("APPROVED", "REJECTED", "DISPUTED"),
            "APPROVED", Set.of("DISPUTED", "COMPLETED", "REJECTED"),
            "DISPUTED", Set.of("APPROVED", "REJECTED"),
            "REJECTED", Set.of(), "COMPLETED", Set.of());
    private final ClaimRepository claims;
    private final ClaimStatusEventRepository events;
    ClaimController(ClaimRepository claims, ClaimStatusEventRepository events) {
        this.claims = claims; this.events = events;
    }

    record CreateClaim(String id, String itemId, String claimantId, String description, String operationId) {}
    record UpdateStatus(String status, String operationId) {}
    record ClaimView(String id, String itemId, String claimantId, String description, String status,
            Instant createdAt, Instant updatedAt) {
        static ClaimView of(Claim c) { return new ClaimView(c.id, c.itemId, c.claimantId, c.description,
                c.status, c.createdAt, c.updatedAt); }
    }

    @PostMapping("/internal/claims") @Transactional
    ClaimView create(@RequestBody CreateClaim request) {
        require(request.itemId(), "itemId"); require(request.claimantId(), "claimantId");
        require(request.description(), "description");
        String op = blankToNull(request.operationId());
        if (op != null) {
            var previous = claims.findByCreationOperationId(op);
            if (previous.isPresent()) return ClaimView.of(previous.get());
        }
        Claim c = new Claim();
        c.id = blankToNull(request.id()) != null ? request.id() : UUID.randomUUID().toString();
        if (claims.existsById(c.id)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Claim ID exists");
        c.itemId = request.itemId().trim(); c.claimantId = request.claimantId().trim();
        c.description = request.description().trim(); c.status = "SUBMITTED"; c.creationOperationId = op;
        c.createdAt = Instant.now(); c.updatedAt = c.createdAt;
        return ClaimView.of(claims.save(c));
    }

    @GetMapping("/internal/claims/{id}")
    ClaimView get(@PathVariable String id) { return ClaimView.of(find(id)); }

    @GetMapping("/internal/claims")
    List<ClaimView> search(@RequestParam(required = false) String itemId,
            @RequestParam(required = false) String claimantId) {
        return claims.search(blankToNull(itemId), blankToNull(claimantId), PageRequest.of(0, 100))
                .stream().map(ClaimView::of).toList();
    }

    @PostMapping("/internal/claims/{id}/status") @Transactional
    ClaimView changeStatus(@PathVariable String id, @RequestBody UpdateStatus request) {
        require(request.status(), "status"); require(request.operationId(), "operationId");
        var previous = events.findById(request.operationId());
        if (previous.isPresent()) {
            if (!previous.get().claimId.equals(id) || !previous.get().toStatus.equals(request.status()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Operation ID reused");
            return ClaimView.of(find(id));
        }
        Claim c = find(id);
        if (!ALLOWED.getOrDefault(c.status, Set.of()).contains(request.status()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Invalid status transition from " + c.status);
        ClaimStatusEvent event = new ClaimStatusEvent();
        event.operationId = request.operationId(); event.claimId = id;
        event.fromStatus = c.status; event.toStatus = request.status(); event.createdAt = Instant.now();
        c.status = request.status(); c.updatedAt = Instant.now();
        claims.save(c); events.save(event);
        return ClaimView.of(c);
    }

    private Claim find(String id) { return claims.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Claim not found")); }
    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    private static void require(String s, String name) { if (blankToNull(s) == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " required"); }
}
