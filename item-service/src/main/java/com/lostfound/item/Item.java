package com.lostfound.item;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity @Table(name = "items")
class Item {
    @Id @Column(length = 36) String id;
    @Column(nullable = false, length = 160) String title;
    @Column(nullable = false, length = 80) String category;
    @Column(nullable = false, length = 2000) String publicDescription;
    @Column(nullable = false, length = 200) String foundLocation;
    @Column(nullable = false) Instant foundAt;
    @Column(nullable = false) boolean valuable;
    @Column(nullable = false, length = 24) String status;
    @Column(length = 36) String reservedClaimId;
    @Column(unique = true, length = 100) String creationOperationId;
    @Column(nullable = false) Instant createdAt;
    @Column(nullable = false) Instant updatedAt;
}
