package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * Stored hardness of one constraint (plan §12.1) — audit surface in v1:
 * planning derives hardness from {@link AvailabilityConstraintType}
 * alone (BUSY/AVAILABLE_ONLY hard, PREFERRED/AVOID soft), and an
 * uncertain ("SOFT") busy interval still blocks, per the conservative
 * rule of spec §12.3 ("ambiguous → treat as unavailable").
 */
public enum AvailabilityConstraintHardness {
    HARD,
    SOFT
}
