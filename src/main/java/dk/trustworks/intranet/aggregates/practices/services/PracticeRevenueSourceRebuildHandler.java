package dk.trustworks.intranet.aggregates.practices.services;

import java.time.LocalDate;
import java.math.BigInteger;
import java.util.UUID;

/** Closed source-rebuild delegation used only after a stale owner is safely cleared. */
public interface PracticeRevenueSourceRebuildHandler {
    PracticeRevenueSourceRecoveryService.Category category();

    RebuildResult rebuild(RebuildRequest request);

    record RebuildRequest(LocalDate fromInclusive, LocalDate toInclusive, String recoveryToken,
                          BigInteger recoveryTargetFactChangeLogId) {
        public RebuildRequest {
            if (fromInclusive == null || toInclusive == null || fromInclusive.isAfter(toInclusive)) {
                throw new IllegalArgumentException("invalid source rebuild bounds");
            }
            if(recoveryToken==null||!UUID.fromString(recoveryToken).toString().equals(recoveryToken)){
                throw new IllegalArgumentException("canonical recovery token is required");
            }
        }
    }

    record RebuildResult(boolean complete, String safeCode, BigInteger observedFactChangeLogId) {
        public static RebuildResult success() {
            return new RebuildResult(true, "SOURCE_REBUILD_COMPLETE", null);
        }
        public static RebuildResult deliverySuccess(BigInteger observedFactChangeLogId) {
            if(observedFactChangeLogId==null||observedFactChangeLogId.signum()<0){
                throw new IllegalArgumentException("delivery cursor is required");
            }
            return new RebuildResult(true,"SOURCE_REBUILD_COMPLETE",observedFactChangeLogId);
        }
    }
}
