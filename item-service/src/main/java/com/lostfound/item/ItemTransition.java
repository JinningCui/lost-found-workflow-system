package com.lostfound.item;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity @Table(name = "item_transitions")
class ItemTransition {
    @Id @Column(length = 100) String operationId;
    @Column(nullable = false, length = 36) String itemId;
    @Column(nullable = false, length = 24) String fromStatus;
    @Column(nullable = false, length = 24) String toStatus;
    @Column(length = 36) String claimId;
    @Column(nullable = false) Instant createdAt;
}
