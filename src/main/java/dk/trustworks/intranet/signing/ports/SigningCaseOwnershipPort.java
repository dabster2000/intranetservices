package dk.trustworks.intranet.signing.ports;

import java.util.UUID;

/**
 * Outbound port owned by the signing context that lets other bounded
 * contexts (recruitment in particular) transfer the local-database owner
 * of a {@code SigningCase} without having to depend on signing-domain
 * entities or repositories directly.
 * <p>
 * "Local owner" refers only to {@code signing_cases.user_uuid}, which our
 * application uses for filtered list queries; it does NOT change anything
 * in NextSign's external system.
 */
public interface SigningCaseOwnershipPort {

    /**
     * Re-point a signing case row to a new local owner. Idempotent if the
     * case is already owned by {@code newUserUuid}. No-op rows-affected = 0
     * if the {@code caseKey} is unknown — callers that care should perform
     * an existence check first.
     * <p>
     * <strong>Transaction contract:</strong> this method must run inside
     * the caller's {@code @Transactional} boundary. The implementation
     * deliberately does not start its own transaction so that ownership
     * transfer participates in the same atomic unit as the recruitment
     * "candidate hired" workflow that triggers it.
     *
     * @param caseKey     the {@code signing_cases.case_key} value (NextSign id)
     * @param newUserUuid UUID of the {@code users} row that should become the
     *                    new local owner
     */
    void transferLocalOwner(String caseKey, UUID newUserUuid);
}
