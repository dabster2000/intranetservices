package dk.trustworks.intranet.signing.ports;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.Objects;
import java.util.UUID;

/**
 * Default implementation of {@link SigningCaseOwnershipPort} that issues a
 * single parameterised {@code UPDATE} against {@code signing_cases}. Runs
 * within the caller's transaction (no {@code @Transactional} on this class)
 * so the ownership transfer composes atomically with the calling workflow.
 */
@ApplicationScoped
public class SigningCaseOwnershipPortImpl implements SigningCaseOwnershipPort {

    @Inject
    EntityManager em;

    @Override
    public void transferLocalOwner(String caseKey, UUID newUserUuid) {
        Objects.requireNonNull(caseKey, "caseKey must not be null");
        Objects.requireNonNull(newUserUuid, "newUserUuid must not be null");

        em.createNativeQuery(
                "UPDATE signing_cases SET user_uuid = :newUserUuid WHERE case_key = :caseKey")
                .setParameter("newUserUuid", newUserUuid.toString())
                .setParameter("caseKey", caseKey)
                .executeUpdate();
    }
}
