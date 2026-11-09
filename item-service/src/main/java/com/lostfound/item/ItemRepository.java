package com.lostfound.item;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ItemRepository extends JpaRepository<Item, String> {
    Optional<Item> findByCreationOperationId(String operationId);

    @Query("select i from Item i where (:category is null or lower(i.category) = lower(:category)) and " +
           "(:status is null or i.status = :status) and " +
           "(:keyword is null or lower(i.title) like lower(concat('%', :keyword, '%')) or " +
           "lower(i.publicDescription) like lower(concat('%', :keyword, '%')) or " +
           "lower(i.foundLocation) like lower(concat('%', :keyword, '%'))) order by i.createdAt desc")
    List<Item> search(@Param("keyword") String keyword, @Param("category") String category,
            @Param("status") String status, Pageable limit);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Item i set i.status = :toStatus, i.reservedClaimId = :claimId, i.updatedAt = :now " +
           "where i.id = :id and i.status = :fromStatus and " +
           "(:expectedClaimId is null or i.reservedClaimId = :expectedClaimId)")
    int transition(@Param("id") String id, @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus, @Param("claimId") String claimId,
            @Param("expectedClaimId") String expectedClaimId, @Param("now") Instant now);
}

interface ItemTransitionRepository extends JpaRepository<ItemTransition, String> {}
