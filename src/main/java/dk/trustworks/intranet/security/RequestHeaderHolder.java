package dk.trustworks.intranet.security;

import lombok.Data;

import jakarta.enterprise.context.RequestScoped;

@Data
@RequestScoped
public class RequestHeaderHolder {
    private String userUuid;

    /**
     * The real admin behind an impersonated session, from {@code X-Acting-For}, or
     * {@code null} for an ordinary request.
     *
     * <p>During impersonation {@code userUuid} IS the impersonated subject — the swap
     * happens in the BFF's JWT — so the backend otherwise behaves exactly as it would for
     * that person. This field is the only thing that discloses the actor, which is why
     * surfaces that record evidence about a person consult it: the competence module
     * refuses course completions, test attempts and approval decisions while it is set
     * (competence spec §10.4). Impersonated evidence is worse than no evidence.
     */
    private String actingForUuid;

    /** Whether this request is being made by an admin impersonating someone else. */
    public boolean isImpersonated() {
        return actingForUuid != null && !actingForUuid.isBlank();
    }
}
