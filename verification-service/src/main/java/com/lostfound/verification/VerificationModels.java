package com.lostfound.verification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity @Table(name = "hidden_clues")
class Clue {
    @Id @Column(length = 36) String id;
    @Column(nullable = false, length = 36) String itemId;
    @Column(nullable = false, length = 300) String question;
    @Column(nullable = false, length = 2000) String answerCiphertext;
    @Column(nullable = false) Instant createdAt;
}

@Entity @Table(name = "clue_batches")
class ClueBatch {
    @Id @Column(length = 100) String operationId;
    @Column(nullable = false, length = 36) String itemId;
    @Column(nullable = false) Instant createdAt;
}

@Entity @Table(name = "evidence")
class Evidence {
    @Id @Column(length = 36) String claimId;
    @Column(nullable = false, length = 36) String itemId;
    @Column(nullable = false) boolean preliminaryPassed;
    @Column(nullable = false) int matched;
    @Column(nullable = false) int total;
    @Column(nullable = false) double score;
    @Column(unique = true, nullable = false, length = 100) String operationId;
    @Column(nullable = false) Instant createdAt;
}

@Entity @Table(name = "evidence_answers")
class EvidenceAnswer {
    @Id @Column(length = 36) String id;
    @Column(nullable = false, length = 36) String claimId;
    @Column(nullable = false, length = 300) String question;
    @Column(nullable = false, length = 2000) String answerCiphertext;
}

@Entity @Table(name = "reviews")
class Review {
    @Id @Column(length = 100) String operationId;
    @Column(nullable = false, length = 36) String claimId;
    @Column(nullable = false, length = 100) String reviewerId;
    @Column(nullable = false, length = 50) String stage;
    @Column(nullable = false, length = 30) String decision;
    @Column(length = 2000) String reason;
    @Column(nullable = false) Instant createdAt;
}
