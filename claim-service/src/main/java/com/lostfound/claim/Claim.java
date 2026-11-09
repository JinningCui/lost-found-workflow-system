package com.lostfound.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity @Table(name = "claims")
class Claim {
    @Id @Column(length = 36) String id;
    @Column(nullable = false, length = 36) String itemId;
    @Column(nullable = false, length = 100) String claimantId;
    @Column(nullable = false, length = 2000) String description;
    @Column(nullable = false, length = 24) String status;
    @Column(unique = true, length = 100) String creationOperationId;
    @Column(nullable = false) Instant createdAt;
    @Column(nullable = false) Instant updatedAt;
    @Version long version;
}
