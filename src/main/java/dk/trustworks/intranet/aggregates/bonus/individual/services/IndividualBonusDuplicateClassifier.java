package dk.trustworks.intranet.aggregates.bonus.individual.services;

import org.hibernate.exception.ConstraintViolationException;

import java.util.Locale;
import java.util.Set;

/** Accepts idempotency only for explicitly named unique constraints, never for arbitrary failures. */
final class IndividualBonusDuplicateClassifier {
    private IndividualBonusDuplicateClassifier() { }

    static boolean isNamedUniqueViolation(Throwable failure, Set<String> acceptedConstraintNames) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof ConstraintViolationException constraint
                    && accepted(constraint.getConstraintName(), acceptedConstraintNames)) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("duplicate")) {
                for (String accepted : acceptedConstraintNames) {
                    if (message.contains(accepted)) return true;
                }
            }
        }
        return false;
    }

    private static boolean accepted(String actual, Set<String> accepted) {
        if (actual == null) return false;
        String normalized = actual.replace("`", "").replace("'", "");
        return accepted.stream().anyMatch(name -> normalized.equals(name) || normalized.endsWith("." + name));
    }
}
