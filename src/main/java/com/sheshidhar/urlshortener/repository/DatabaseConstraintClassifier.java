package com.sheshidhar.urlshortener.repository;

import org.hibernate.exception.ConstraintViolationException;
import org.postgresql.util.PSQLException;
import org.springframework.stereotype.Component;

@Component
public class DatabaseConstraintClassifier {

    static final String SHORT_CODE_UNIQUE = "uq_url_mappings_short_code";
    static final String IDEMPOTENCY_KEY_UNIQUE = "uq_url_mappings_idempotency_key";

    public DatabaseConstraint classify(Throwable failure) {
        String constraintName = findConstraintName(failure);
        if (SHORT_CODE_UNIQUE.equals(constraintName)) {
            return DatabaseConstraint.SHORT_CODE_UNIQUE;
        }
        if (IDEMPOTENCY_KEY_UNIQUE.equals(constraintName)) {
            return DatabaseConstraint.IDEMPOTENCY_KEY_UNIQUE;
        }
        return DatabaseConstraint.OTHER;
    }

    private String findConstraintName(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ConstraintViolationException constraintViolation
                    && constraintViolation.getConstraintName() != null) {
                return constraintViolation.getConstraintName();
            }
            if (current instanceof PSQLException postgresFailure
                    && postgresFailure.getServerErrorMessage() != null
                    && postgresFailure.getServerErrorMessage().getConstraint() != null) {
                return postgresFailure.getServerErrorMessage().getConstraint();
            }
            current = current.getCause();
        }
        return null;
    }
}
