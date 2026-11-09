package com.lostfound.verification;

import java.text.Normalizer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class VerificationController {
    private final ClueRepository clues;
    private final ClueBatchRepository batches;
    private final EvidenceRepository evidence;
    private final EvidenceAnswerRepository answers;
    private final ReviewRepository reviews;
    VerificationController(ClueRepository clues, ClueBatchRepository batches, EvidenceRepository evidence,
            EvidenceAnswerRepository answers, ReviewRepository reviews) {
        this.clues = clues; this.batches = batches; this.evidence = evidence;
        this.answers = answers; this.reviews = reviews;
    }

    record SaveClues(String itemId, Map<String, String> clues, String operationId) {}
    record ClueView(String itemId, Map<String, String> clues) {}
    record SubmitEvidence(String claimId, String itemId, Map<String, String> answers, String operationId) {}
    record EvidenceView(String claimId, String itemId, boolean preliminaryPassed, double score,
            int matched, int total, Instant createdAt) {
        static EvidenceView of(Evidence e) { return new EvidenceView(e.claimId, e.itemId,
                e.preliminaryPassed, e.score, e.matched, e.total, e.createdAt); }
    }
    record EvidenceDetail(String claimId, String itemId, boolean preliminaryPassed, double score,
            int matched, int total, Instant createdAt, Map<String, String> answers) {}
    record AddReview(String claimId, String reviewerId, String stage, String decision,
            String reason, String operationId) {}
    record ReviewView(String claimId, String reviewerId, String stage, String decision,
            String reason, Instant createdAt) {
        static ReviewView of(Review r) { return new ReviewView(r.claimId, r.reviewerId,
                r.stage, r.decision, r.reason, r.createdAt); }
    }

    @PostMapping("/internal/clues") @Transactional
    ClueView saveClues(@RequestBody SaveClues request) {
        require(request.itemId(), "itemId"); require(request.operationId(), "operationId");
        if (batches.existsById(request.operationId())) {
            ClueBatch previous = batches.findById(request.operationId()).orElseThrow();
            if (!previous.itemId.equals(request.itemId())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Operation ID reused");
            return getClues(request.itemId());
        }
        if (clues.existsByItemId(request.itemId())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Clues already set");
        if (request.clues() == null || request.clues().isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one clue required");
        for (var entry : request.clues().entrySet()) {
            require(entry.getKey(), "question"); require(entry.getValue(), "answer");
            Clue clue = new Clue(); clue.id = UUID.randomUUID().toString(); clue.itemId = request.itemId();
            clue.question = entry.getKey().trim(); clue.answerCiphertext = encrypt(entry.getValue().trim()); clue.createdAt = Instant.now();
            clues.save(clue);
        }
        ClueBatch batch = new ClueBatch(); batch.operationId = request.operationId();
        batch.itemId = request.itemId(); batch.createdAt = Instant.now(); batches.save(batch);
        return getClues(request.itemId());
    }

    @GetMapping("/internal/clues/{itemId}")
    ClueView getClues(@PathVariable String itemId) {
        List<Clue> found = clues.findByItemIdOrderByCreatedAtAsc(itemId);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Clues not found");
        Map<String, String> result = new LinkedHashMap<>();
        found.forEach(c -> result.put(c.question, decrypt(c.answerCiphertext)));
        return new ClueView(itemId, result);
    }

    @GetMapping("/internal/clues/{itemId}/questions")
    List<String> getQuestions(@PathVariable String itemId) {
        return clues.findByItemIdOrderByCreatedAtAsc(itemId).stream().map(c -> c.question).toList();
    }

    @PostMapping("/internal/evidence") @Transactional
    EvidenceView submitEvidence(@RequestBody SubmitEvidence request) {
        require(request.claimId(), "claimId"); require(request.itemId(), "itemId");
        require(request.operationId(), "operationId");
        var byOperation = evidence.findByOperationId(request.operationId());
        if (byOperation.isPresent()) {
            if (!byOperation.get().claimId.equals(request.claimId())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Operation ID reused");
            return EvidenceView.of(byOperation.get());
        }
        if (evidence.existsById(request.claimId())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Evidence already submitted");
        List<Clue> expected = clues.findByItemIdOrderByCreatedAtAsc(request.itemId());
        if (expected.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "No clues configured");
        Map<String, String> submitted = request.answers() == null ? Map.of() : request.answers();
        int matched = 0;
        for (Clue clue : expected) {
            String answer = submitted.get(clue.question);
            if (answer != null && normalized(answer).equals(normalized(decrypt(clue.answerCiphertext)))) matched++;
        }
        Evidence e = new Evidence(); e.claimId = request.claimId(); e.itemId = request.itemId();
        e.matched = matched; e.total = expected.size(); e.score = (double) matched / e.total;
        e.preliminaryPassed = matched == e.total; e.operationId = request.operationId(); e.createdAt = Instant.now();
        evidence.save(e);
        for (var entry : submitted.entrySet()) {
            require(entry.getKey(), "question"); require(entry.getValue(), "answer");
            EvidenceAnswer answer = new EvidenceAnswer(); answer.id = UUID.randomUUID().toString();
            answer.claimId = request.claimId(); answer.question = entry.getKey();
            answer.answerCiphertext = encrypt(entry.getValue());
            answers.save(answer);
        }
        return EvidenceView.of(e);
    }

    @GetMapping("/internal/evidence/{claimId}")
    EvidenceDetail getEvidence(@PathVariable String claimId) {
        Evidence e = evidence.findById(claimId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Evidence not found"));
        Map<String, String> submitted = new LinkedHashMap<>();
        answers.findByClaimId(claimId).forEach(a -> submitted.put(a.question, decrypt(a.answerCiphertext)));
        return new EvidenceDetail(e.claimId, e.itemId, e.preliminaryPassed, e.score,
                e.matched, e.total, e.createdAt, submitted);
    }

    @PostMapping("/internal/reviews") @Transactional
    ReviewView addReview(@RequestBody AddReview request) {
        require(request.claimId(), "claimId"); require(request.reviewerId(), "reviewerId");
        require(request.stage(), "stage"); require(request.decision(), "decision");
        require(request.operationId(), "operationId");
        var prior = reviews.findById(request.operationId());
        if (prior.isPresent()) {
            if (!prior.get().claimId.equals(request.claimId())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Operation ID reused");
            return ReviewView.of(prior.get());
        }
        Review review = new Review(); review.operationId = request.operationId();
        review.claimId = request.claimId(); review.reviewerId = request.reviewerId();
        review.stage = request.stage(); review.decision = request.decision();
        review.reason = request.reason(); review.createdAt = Instant.now();
        return ReviewView.of(reviews.save(review));
    }

    @GetMapping("/internal/reviews")
    List<ReviewView> getReviews(@RequestParam String claimId) {
        require(claimId, "claimId");
        return reviews.findByClaimIdOrderByCreatedAtAsc(claimId).stream().map(ReviewView::of).toList();
    }

    private static String normalized(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFKC).trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
    private static String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[12]; new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, clueKey(), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (java.security.GeneralSecurityException ex) { throw new IllegalStateException("Clue encryption failed", ex); }
    }
    private static String decrypt(String encoded) {
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            if (combined.length < 29) throw new IllegalArgumentException("Invalid ciphertext");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, clueKey(), new GCMParameterSpec(128, Arrays.copyOfRange(combined, 0, 12)));
            return new String(cipher.doFinal(Arrays.copyOfRange(combined, 12, combined.length)), StandardCharsets.UTF_8);
        } catch (java.security.GeneralSecurityException ex) { throw new IllegalStateException("Clue decryption failed", ex); }
    }
    private static SecretKeySpec clueKey() {
        String raw = System.getenv("LF_CLUE_KEY");
        if (raw == null || raw.isBlank()) throw new IllegalStateException("LF_CLUE_KEY is required");
        byte[] bytes = Base64.getDecoder().decode(raw);
        if (bytes.length != 32) throw new IllegalStateException("LF_CLUE_KEY must be Base64 encoded 32 bytes");
        return new SecretKeySpec(bytes, "AES");
    }
    private static void require(String s, String name) {
        if (s == null || s.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " required");
    }
}
