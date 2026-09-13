package dk.trustworks.intranet.aggregates.crm.plan.model;

import dk.trustworks.intranet.aggregates.crm.plan.model.enums.PlanSlot;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite key for {@link ClientPlanSentence}: one sentence per slot per plan.
 *
 * <p>A natural key rather than a surrogate uuid, because that IS the constraint — a plan
 * has exactly one "where we stand today", and a second row for the same slot would be a
 * bug rather than a second opinion.
 */
public class ClientPlanSentenceId implements Serializable {

    private String clientUuid;
    private PlanSlot slot;

    public ClientPlanSentenceId() {
    }

    public ClientPlanSentenceId(String clientUuid, PlanSlot slot) {
        this.clientUuid = clientUuid;
        this.slot = slot;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ClientPlanSentenceId that)) return false;
        return Objects.equals(clientUuid, that.clientUuid) && slot == that.slot;
    }

    @Override
    public int hashCode() {
        return Objects.hash(clientUuid, slot);
    }
}
