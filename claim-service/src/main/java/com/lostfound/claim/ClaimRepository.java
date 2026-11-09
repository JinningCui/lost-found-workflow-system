package com.lostfound.claim;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ClaimRepository extends JpaRepository<Claim, String> {
    Optional<Claim> findByCreationOperationId(String operationId);
    @Query("select c from Claim c where (:itemId is null or c.itemId = :itemId) and " +
           "(:claimantId is null or c.claimantId = :claimantId) order by c.createdAt desc")
    List<Claim> search(@Param("itemId") String itemId, @Param("claimantId") String claimantId, Pageable limit);
}

interface ClaimStatusEventRepository extends JpaRepository<ClaimStatusEvent, String> {}
