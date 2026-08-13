package com.sheshidhar.urlshortener.repository;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseConstraintClassifierTest {

    private final DatabaseConstraintClassifier classifier = new DatabaseConstraintClassifier();

    @Test
    void classifiesShortCodeUniqueConstraint() {
        assertThat(classifier.classify(failureWithConstraint("uq_url_mappings_short_code")))
                .isEqualTo(DatabaseConstraint.SHORT_CODE_UNIQUE);
    }

    @Test
    void classifiesIdempotencyUniqueConstraint() {
        assertThat(classifier.classify(failureWithConstraint("uq_url_mappings_idempotency_key")))
                .isEqualTo(DatabaseConstraint.IDEMPOTENCY_KEY_UNIQUE);
    }

    @Test
    void leavesUnrelatedConstraintUnclassified() {
        assertThat(classifier.classify(failureWithConstraint("chk_url_mappings_expiration")))
                .isEqualTo(DatabaseConstraint.OTHER);
    }

    private DataIntegrityViolationException failureWithConstraint(String constraintName) {
        ConstraintViolationException violation = mock(ConstraintViolationException.class);
        when(violation.getConstraintName()).thenReturn(constraintName);
        return new DataIntegrityViolationException("integrity failure", violation);
    }
}
