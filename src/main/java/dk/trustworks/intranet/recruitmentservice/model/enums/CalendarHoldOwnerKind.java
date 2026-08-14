package dk.trustworks.intranet.recruitmentservice.model.enums;

/** Whose calendar a hold event lives in (D5: one event per owner). */
public enum CalendarHoldOwnerKind {
    /** An interviewer's own calendar. */
    USER,
    /** A room mailbox — a direct write, allowed by tenant policy per the
     * Phase 7.5 spike; it bypasses the room's AutoAccept arbitration, so
     * the calendarView recheck is the conflict guard. */
    ROOM
}
