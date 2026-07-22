package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/**
 * The collapsed terminal rail of the board (P7 contract
 * {@code IPositionBoard.terminal}): per-outcome counts plus the closed
 * applications newest-first. Only the three pipeline terminals appear
 * here — {@code HIRED} is a stage, not a terminal, and renders as a
 * normal column.
 */
public record BoardTerminalSummary(
        long rejected,
        long withdrawn,
        long returnedToPool,
        List<BoardTerminalEntry> applications
) {
}
