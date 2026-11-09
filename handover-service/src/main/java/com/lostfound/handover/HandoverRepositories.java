package com.lostfound.handover;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

interface PickupCodeRepository extends JpaRepository<PickupCode, String> {
    Optional<PickupCode> findByCreationOperationId(String operationId);
    Optional<PickupCode> findByRedemptionOperationId(String operationId);
    List<PickupCode> findByItemIdAndStatusOrderByIssuedAtDesc(String itemId, String status);
    Optional<PickupCode> findFirstByClaimIdAndStatusOrderByIssuedAtDesc(String claimId, String status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PickupCode c where c.itemId = :itemId and c.claimId = :claimId and c.status = 'ACTIVE' order by c.issuedAt desc")
    List<PickupCode> lockActive(@Param("itemId") String itemId, @Param("claimId") String claimId);
}
interface CodeActionRepository extends JpaRepository<CodeAction, String> {}
interface HandoverRecordRepository extends JpaRepository<HandoverRecord, String> {
    List<HandoverRecord> findByItemId(String itemId);
}
interface TransferRecordRepository extends JpaRepository<TransferRecord, String> {
    List<TransferRecord> findByItemId(String itemId);
    boolean existsByItemId(String itemId);
}
