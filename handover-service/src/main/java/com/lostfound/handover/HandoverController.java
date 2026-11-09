package com.lostfound.handover;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class HandoverController {
    private final PickupCodeRepository codes;
    private final CodeActionRepository actions;
    private final HandoverRecordRepository handovers;
    private final TransferRecordRepository transfers;
    HandoverController(PickupCodeRepository codes, CodeActionRepository actions,
            HandoverRecordRepository handovers, TransferRecordRepository transfers) {
        this.codes = codes; this.actions = actions; this.handovers = handovers; this.transfers = transfers;
    }

    record CreateCode(String itemId, String claimId, String operationId) {}
    record RevokeCode(String itemId, String claimId, String operationId) {}
    record RedeemCode(String itemId, String claimId, String code, String staffId, String operationId) {}
    record CreateTransfer(String itemId, String destination, String operatorId, String operationId) {}
    record CodeView(String itemId, String claimId, String code, Instant expiresAt, String status) {}
    record RecordView(String type, String itemId, String claimId, String actorId, String destination, Instant createdAt) {}

    @PostMapping("/internal/codes") @Transactional
    CodeView create(@RequestBody CreateCode request) {
        require(request.itemId(), "itemId"); require(request.claimId(), "claimId");
        require(request.operationId(), "operationId");
        var previous = codes.findByCreationOperationId(request.operationId());
        if (previous.isPresent()) {
            PickupCode c = previous.get();
            if (!c.itemId.equals(request.itemId()) || !c.claimId.equals(request.claimId()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Operation ID reused");
            return view(c);
        }
        List<PickupCode> active = codes.findByItemIdAndStatusOrderByIssuedAtDesc(request.itemId(), "ACTIVE");
        for (PickupCode c : active) {
            if (c.expiresAt.isAfter(Instant.now())) {
                if (c.claimId.equals(request.claimId())) return view(c);
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Item has an active pickup code");
            }
            c.status = "EXPIRED"; codes.save(c);
        }
        PickupCode c = new PickupCode(); c.id = UUID.randomUUID().toString();
        c.itemId = request.itemId(); c.claimId = request.claimId(); c.status = "ACTIVE";
        c.issuedAt = Instant.now(); c.expiresAt = c.issuedAt.plus(24, ChronoUnit.HOURS);
        c.creationOperationId = request.operationId(); c.codeHash = hash(plainCode(c));
        codes.save(c);
        return view(c);
    }

    @GetMapping("/internal/codes")
    CodeView getActive(@RequestParam String claimId) {
        require(claimId, "claimId");
        PickupCode c = codes.findFirstByClaimIdAndStatusOrderByIssuedAtDesc(claimId, "ACTIVE")
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Active code not found"));
        if (!c.expiresAt.isAfter(Instant.now())) throw new ResponseStatusException(HttpStatus.GONE, "Code expired");
        return view(c);
    }

    @PostMapping("/internal/codes/revoke") @Transactional
    int revoke(@RequestBody RevokeCode request) {
        require(request.itemId(), "itemId"); require(request.operationId(), "operationId");
        if (actions.existsById(request.operationId())) {
            CodeAction previous = actions.findById(request.operationId()).orElseThrow();
            if (!previous.itemId.equals(request.itemId()) || !previous.action.equals("REVOKE"))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Operation ID reused");
            return 0;
        }
        int revoked = 0;
        for (PickupCode c : codes.findByItemIdAndStatusOrderByIssuedAtDesc(request.itemId(), "ACTIVE")) {
            if (request.claimId() == null || c.claimId.equals(request.claimId())) {
                c.status = "REVOKED"; codes.save(c); revoked++;
            }
        }
        CodeAction action = new CodeAction(); action.operationId = request.operationId();
        action.action = "REVOKE"; action.itemId = request.itemId(); action.claimId = request.claimId();
        action.createdAt = Instant.now(); actions.save(action);
        return revoked;
    }

    @PostMapping("/internal/codes/redeem") @Transactional(noRollbackFor = ResponseStatusException.class)
    RecordView redeem(@RequestBody RedeemCode request) {
        require(request.itemId(), "itemId"); require(request.claimId(), "claimId");
        require(request.code(), "code"); require(request.staffId(), "staffId");
        require(request.operationId(), "operationId");
        Optional<PickupCode> done = codes.findByRedemptionOperationId(request.operationId());
        if (done.isPresent()) {
            PickupCode c = done.get();
            if (!c.itemId.equals(request.itemId()) || !c.claimId.equals(request.claimId()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Operation ID reused");
            return recordFor(c);
        }
        List<PickupCode> active = codes.lockActive(request.itemId(), request.claimId());
        if (active.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "No active pickup code");
        PickupCode c = active.get(0);
        if (!c.expiresAt.isAfter(Instant.now())) {
            c.status = "EXPIRED"; codes.save(c);
            throw new ResponseStatusException(HttpStatus.GONE, "Code expired");
        }
        if (c.attempts >= 5) throw new ResponseStatusException(HttpStatus.LOCKED, "Too many attempts");
        if (!MessageDigest.isEqual(c.codeHash.getBytes(StandardCharsets.UTF_8),
                hash(request.code()).getBytes(StandardCharsets.UTF_8))) {
            c.attempts++;
            if (c.attempts >= 5) c.status = "BLOCKED";
            codes.save(c);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid pickup code");
        }
        c.status = "REDEEMED"; c.staffId = request.staffId();
        c.redemptionOperationId = request.operationId(); codes.save(c);
        HandoverRecord record = new HandoverRecord(); record.operationId = request.operationId();
        record.itemId = c.itemId; record.claimId = c.claimId; record.staffId = request.staffId();
        record.createdAt = Instant.now(); handovers.save(record);
        return recordFor(c);
    }

    @PostMapping("/internal/transfers") @Transactional
    RecordView transfer(@RequestBody CreateTransfer request) {
        require(request.itemId(), "itemId"); require(request.destination(), "destination");
        require(request.operatorId(), "operatorId"); require(request.operationId(), "operationId");
        var prior = transfers.findById(request.operationId());
        if (prior.isPresent()) {
            if (!prior.get().itemId.equals(request.itemId())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Operation ID reused");
            return recordFor(prior.get());
        }
        if (transfers.existsByItemId(request.itemId())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Item already transferred");
        TransferRecord record = new TransferRecord(); record.operationId = request.operationId();
        record.itemId = request.itemId(); record.destination = request.destination().trim();
        record.operatorId = request.operatorId().trim(); record.createdAt = Instant.now();
        return recordFor(transfers.save(record));
    }

    @GetMapping("/internal/records")
    List<RecordView> records(@RequestParam String itemId) {
        require(itemId, "itemId");
        List<RecordView> result = new ArrayList<>();
        handovers.findByItemId(itemId).forEach(r -> result.add(recordFor(r)));
        transfers.findByItemId(itemId).forEach(r -> result.add(recordFor(r)));
        result.sort(Comparator.comparing(RecordView::createdAt));
        return result;
    }

    private CodeView view(PickupCode c) {
        return new CodeView(c.itemId, c.claimId, c.status.equals("ACTIVE") && c.expiresAt.isAfter(Instant.now()) ? plainCode(c) : null,
                c.expiresAt, c.status);
    }
    private RecordView recordFor(PickupCode c) {
        return handovers.findById(c.redemptionOperationId).map(this::recordFor).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.CONFLICT, "Handover record missing"));
    }
    private RecordView recordFor(HandoverRecord r) {
        return new RecordView("HANDOVER", r.itemId, r.claimId, r.staffId, null, r.createdAt);
    }
    private RecordView recordFor(TransferRecord r) {
        return new RecordView("TRANSFER", r.itemId, null, r.operatorId, r.destination, r.createdAt);
    }
    private String plainCode(PickupCode c) {
        byte[] digest = hmac("code:" + c.id + ":" + c.itemId + ":" + c.claimId);
        long number = Long.remainderUnsigned(ByteBuffer.wrap(digest, 0, 8).getLong(), 10_000_000_000L);
        return String.format("%010d", number);
    }
    private String hash(String code) { return Base64.getEncoder().encodeToString(hmac("hash:" + code)); }
    private byte[] hmac(String value) {
        try {
            String secret = System.getenv("LF_INTERNAL_TOKEN");
            if (secret == null || secret.isBlank()) throw new IllegalStateException("LF_INTERNAL_TOKEN is required");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException ex) { throw new IllegalStateException(ex); }
    }
    private static void require(String s, String name) {
        if (s == null || s.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " required");
    }
}
