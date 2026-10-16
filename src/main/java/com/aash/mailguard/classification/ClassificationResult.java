package com.aash.mailguard.classification;

import java.time.Instant;
import java.util.Objects;

public record ClassificationResult(EvidenceClassification classification, ClassificationReasonCode reason,
                                   EvidenceSource source, ValidationEvidence evidence, boolean retryable,
                                   Instant classifiedAt, String diagnostic) {
    public ClassificationResult {
        Objects.requireNonNull(classification);
        Objects.requireNonNull(reason);
        Objects.requireNonNull(source);
        Objects.requireNonNull(evidence);
        Objects.requireNonNull(classifiedAt);
        Objects.requireNonNull(diagnostic);
    }
}
