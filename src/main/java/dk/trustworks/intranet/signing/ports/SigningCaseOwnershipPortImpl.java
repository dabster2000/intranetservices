package dk.trustworks.intranet.signing.ports;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.Objects;
import java.util.UUID;

@ApplicationScoped
public class SigningCaseOwnershipPortImpl implements SigningCaseOwnershipPort {

    @Inject
    EntityManager em;

    @Override
    public void transferLocalOwner(String caseKey, UUID newUserUuid) {
        Objects.requireNonNull(caseKey, "caseKey must not be null");
        Objects.requireNonNull(newUserUuid, "newUserUuid must not be null");

        int rows = em.createNativeQuery(
                "UPDATE signing_cases SET user_uuid = :newUserUuid WHERE case_key = :caseKey")
                .setParameter("newUserUuid", newUserUuid.toString())
                .setParameter("caseKey", caseKey)
                .executeUpdate();

        if (rows == 0) {
            throw new SigningCaseNotFoundException(caseKey);
        }
    }
}
