package com.lostfound.handover;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity @Table(name = "pickup_codes")
class PickupCode {
    @Id @Column(length = 36) String id;
    @Column(nullable = false, length = 36) String itemId;
    @Column(nullable = false, length = 36) String claimId;
    @Column(nullable = false, length = 100) String codeHash;
    @Column(nullable = false, length = 24) String status;
    @Column(nullable = false) int attempts;
    @Column(nullable = false) Instant expiresAt;
    @Column(nullable = false) Instant issuedAt;
    @Column(length = 100) String staffId;
    @Column(unique = true, length = 100) String creationOperationId;
    @Column(unique = true, length = 100) String redemptionOperationId;
}

@Entity @Table(name = "code_actions")
class CodeAction {
    @Id @Column(length = 100) String operationId;
    @Column(nullable = false, length = 24) String action;
    @Column(nullable = false, length = 36) String itemId;
    @Column(length = 36) String claimId;
    @Column(nullable = false) Instant createdAt;
}

@Entity @Table(name = "handover_records")
class HandoverRecord {
    @Id @Column(length = 100) String operationId;
    @Column(nullable = false, length = 36) String itemId;
    @Column(nullable = false, length = 36) String claimId;
    @Column(nullable = false, length = 100) String staffId;
    @Column(nullable = false) Instant createdAt;
}

@Entity @Table(name = "transfer_records")
class TransferRecord {
    @Id @Column(length = 100) String operationId;
    @Column(nullable = false, unique = true, length = 36) String itemId;
    @Column(nullable = false, length = 300) String destination;
    @Column(nullable = false, length = 100) String operatorId;
    @Column(nullable = false) Instant createdAt;
}
