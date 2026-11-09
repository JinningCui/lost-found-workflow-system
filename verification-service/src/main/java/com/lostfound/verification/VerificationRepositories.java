package com.lostfound.verification;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface ClueRepository extends JpaRepository<Clue, String> {
    List<Clue> findByItemIdOrderByCreatedAtAsc(String itemId);
    boolean existsByItemId(String itemId);
}
interface ClueBatchRepository extends JpaRepository<ClueBatch, String> {}
interface EvidenceRepository extends JpaRepository<Evidence, String> {
    Optional<Evidence> findByOperationId(String operationId);
}
interface EvidenceAnswerRepository extends JpaRepository<EvidenceAnswer, String> {
    List<EvidenceAnswer> findByClaimId(String claimId);
}
interface ReviewRepository extends JpaRepository<Review, String> {
    List<Review> findByClaimIdOrderByCreatedAtAsc(String claimId);
}
