package dk.trustworks.intranet.aggregates.invoice.selfbilled.dto;

import java.util.List;

/**
 * History-queue row (AC5): one stamped (consultant, work-period) whose booked
 * internals != assigned self-billed total. proposedDelta = assigned - booked
 * (positive -> top-up internal, negative -> credit note, via the normal settle path).
 * Normalized positive amounts; consultant fields null when masked.
 */
public record HistoryRow(String consultantUuid, String consultantName, int workYear, int workMonth,
                         double booked, double assigned, double proposedDelta,
                         List<String> internalUuids) {}
