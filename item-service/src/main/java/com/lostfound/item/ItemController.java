package com.lostfound.item;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class ItemController {
    private static final Set<String> STATES = Set.of("PUBLISHED", "RESERVED", "DISPUTED", "HANDING_OVER", "HANDED_OVER", "DISPOSED");
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "PUBLISHED", Set.of("RESERVED", "DISPUTED", "DISPOSED"),
            "RESERVED", Set.of("PUBLISHED", "DISPUTED", "HANDING_OVER"),
            "DISPUTED", Set.of("PUBLISHED", "RESERVED"),
            "HANDING_OVER", Set.of("HANDED_OVER", "RESERVED"),
            "HANDED_OVER", Set.of(), "DISPOSED", Set.of());

    private final ItemRepository items;
    private final ItemTransitionRepository transitions;
    ItemController(ItemRepository items, ItemTransitionRepository transitions) {
        this.items = items; this.transitions = transitions;
    }

    record ItemView(String id, String title, String category, String publicDescription, String description,
            String foundLocation, Instant foundAt, boolean valuable, boolean highValue, String status,
            Instant createdAt, Instant updatedAt) {
        static ItemView of(Item i) {
            return new ItemView(i.id, i.title, i.category, i.publicDescription, i.publicDescription,
                    i.foundLocation, i.foundAt, i.valuable, i.valuable, i.status, i.createdAt, i.updatedAt);
        }
    }
    record InternalItemView(String id, String title, String category, String publicDescription,
            String description, String foundLocation, Instant foundAt, boolean valuable, boolean highValue,
            String status, String reservedClaimId, Instant createdAt, Instant updatedAt) {
        static InternalItemView of(Item i) {
            return new InternalItemView(i.id, i.title, i.category, i.publicDescription, i.publicDescription,
                    i.foundLocation, i.foundAt, i.valuable, i.valuable, i.status, i.reservedClaimId,
                    i.createdAt, i.updatedAt);
        }
    }
    record CreateItem(String id, String title, String category, String publicDescription, String description,
            String foundLocation, Instant foundAt, Boolean valuable, Boolean highValue, String operationId) {}
    record ChangeStatus(String from, String to, String claimId, String operationId) {}

    @GetMapping("/items")
    List<ItemView> search(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status) {
        String term = blankToNull(keyword) != null ? keyword : query;
        return items.search(blankToNull(term), blankToNull(category), blankToNull(status), PageRequest.of(0, 100))
                .stream().map(ItemView::of).toList();
    }

    @GetMapping("/items/{id}")
    ItemView getPublic(@PathVariable String id) { return ItemView.of(getItem(id)); }

    @GetMapping("/internal/items/{id}")
    InternalItemView getInternal(@PathVariable String id) { return InternalItemView.of(getItem(id)); }

    @PostMapping("/internal/items") @Transactional
    InternalItemView create(@RequestBody CreateItem request) {
        String op = blankToNull(request.operationId());
        if (op != null) {
            var prior = items.findByCreationOperationId(op);
            if (prior.isPresent()) return InternalItemView.of(prior.get());
        }
        String description = blankToNull(request.publicDescription()) != null ? request.publicDescription() : request.description();
        require(request.title(), "title"); require(request.category(), "category");
        require(description, "publicDescription"); require(request.foundLocation(), "foundLocation");
        Item i = new Item();
        i.id = blankToNull(request.id()) != null ? request.id() : UUID.randomUUID().toString();
        if (items.existsById(i.id)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Item ID exists");
        i.title = request.title().trim(); i.category = request.category().trim();
        i.publicDescription = description.trim(); i.foundLocation = request.foundLocation().trim();
        i.foundAt = request.foundAt() != null ? request.foundAt() : Instant.now();
        i.valuable = Boolean.TRUE.equals(request.valuable()) || Boolean.TRUE.equals(request.highValue());
        i.status = "PUBLISHED"; i.creationOperationId = op; i.createdAt = Instant.now(); i.updatedAt = i.createdAt;
        return InternalItemView.of(items.save(i));
    }

    @PostMapping("/internal/items/{id}/transition") @Transactional
    InternalItemView transition(@PathVariable String id, @RequestBody ChangeStatus request) {
        require(request.operationId(), "operationId");
        var previous = transitions.findById(request.operationId());
        if (previous.isPresent()) {
            ItemTransition event = previous.get();
            if (!event.itemId.equals(id) || !event.fromStatus.equals(request.from()) || !event.toStatus.equals(request.to()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Operation ID reused");
            return InternalItemView.of(getItem(id));
        }
        if (!STATES.contains(request.from()) || !STATES.contains(request.to()) || !ALLOWED.get(request.from()).contains(request.to()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid status transition");
        Item current = getItem(id);
        if (!current.status.equals(request.from())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Current status: " + current.status);
        if ((request.to().equals("RESERVED") || request.to().equals("HANDING_OVER") || request.to().equals("HANDED_OVER"))
                && blankToNull(request.claimId()) == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "claimId required");
        String targetClaim = switch (request.to()) {
            case "PUBLISHED", "DISPOSED" -> null;
            case "DISPUTED" -> current.reservedClaimId;
            default -> request.claimId();
        };
        String expectedClaim = switch (request.from()) {
            case "RESERVED", "HANDING_OVER" -> current.reservedClaimId;
            default -> null;
        };
        if (expectedClaim != null && !request.to().equals("PUBLISHED") && !request.to().equals("DISPUTED")
                && !expectedClaim.equals(request.claimId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Claim does not own reservation");
        int updated = items.transition(id, request.from(), request.to(), targetClaim, expectedClaim, Instant.now());
        if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Concurrent status change");
        ItemTransition event = new ItemTransition();
        event.operationId = request.operationId(); event.itemId = id; event.fromStatus = request.from();
        event.toStatus = request.to(); event.claimId = request.claimId(); event.createdAt = Instant.now();
        transitions.save(event);
        return InternalItemView.of(getItem(id));
    }

    private Item getItem(String id) { return items.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found")); }
    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    private static void require(String s, String name) { if (blankToNull(s) == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " required"); }
}
