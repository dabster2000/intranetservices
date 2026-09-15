package dk.trustworks.intranet.aggregates.crm.calendar.dto;

import java.time.Instant;

/** Aggregate coverage among currently consenting mailboxes; no mailbox identifiers. */
public record CalendarSyncHealthDTO(
        int enabled,
        int succeeded,
        int failed,
        int incomplete,
        int neverSynced,
        int inProgress,
        int interrupted,
        Instant lastSuccessfulAt,
        Instant lastFullSuccessfulAt) { }
