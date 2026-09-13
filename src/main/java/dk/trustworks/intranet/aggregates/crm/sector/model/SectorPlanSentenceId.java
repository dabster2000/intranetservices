package dk.trustworks.intranet.aggregates.crm.sector.model;

import dk.trustworks.intranet.aggregates.crm.plan.model.enums.PlanSlot;
import dk.trustworks.intranet.dao.crm.model.enums.ClientSegment;

import java.io.Serializable;
import java.util.Objects;

/** Composite key of {@link SectorPlanSentence}: the segment and the slot. */
public class SectorPlanSentenceId implements Serializable {

    private ClientSegment segment;
    private PlanSlot slot;

    public SectorPlanSentenceId() {
    }

    public SectorPlanSentenceId(ClientSegment segment, PlanSlot slot) {
        this.segment = segment;
        this.slot = slot;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof SectorPlanSentenceId that)) return false;
        return segment == that.segment && slot == that.slot;
    }

    @Override
    public int hashCode() {
        return Objects.hash(segment, slot);
    }
}
