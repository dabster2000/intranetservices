package dk.trustworks.intranet.aggregates.invoice.selfbilled.dto;

/**
 * AC9 capture<->phantom tie-out for the window: capturedNet (normalized) vs the
 * PHANTOM-imported revenue for the same entry numbers. A mismatch is DISPLAYED,
 * never silently ignored (capture gap or import gap).
 */
public record SelfBilledTieOut(double capturedNet, double phantomImported, double delta,
                               int capturedEntries, int matchedPhantoms, boolean ok) {}
