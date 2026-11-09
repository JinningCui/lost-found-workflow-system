package com.lostfound.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity @Table(name = "claim_status_events")
class ClaimStatusEvent {
    @Id @Column(length = 100) String operationId;
    @Column(nullable = false, length = 36) String claimId;
    @Column(nullable = false, length = 24) String fromStatus;
    @Column(nullable = false, length = 24) String toStatus;
    @Column(nullable = false) Instant createdAt;
}
