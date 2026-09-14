package dk.trustworks.intranet.aggregates.crm.calendar.services;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * The one clock every calendar question is asked on.
 *
 * <p>{@code account_meeting.occurred_at} is a Copenhagen wall-clock time: the sync asks Graph
 * for {@code Prefer: outlook.timezone="Europe/Copenhagen"} and stores what comes back, and
 * the column has no zone of its own. The JVM, on the other hand, runs on UTC in the
 * container. So "is this meeting in the past" cannot be asked with {@code LocalDateTime.now()}
 * — for two hours every summer afternoon the answer would be wrong in the direction that puts
 * a meeting still in progress on the timeline — and it must not be asked with the database's
 * {@code NOW()} either, which is UTC as well. Every read path that compares against
 * {@code occurred_at} takes its "now" from here, and so does the sync when it decides which
 * rows are in the future.
 *
 * <p>Deliberately not in {@code DateUtils}: that class answers business-date questions
 * (fiscal years, month bounds) on the system zone, and a second notion of "now" hiding in it
 * would be exactly the trap this class exists to close.
 */
public final class CalendarTime {

    /** The zone the calendar is read in, and the zone {@code occurred_at} is written in. */
    public static final ZoneId ZONE = ZoneId.of("Europe/Copenhagen");

    private CalendarTime() {
    }

    /** Now, on the wall clock {@code account_meeting.occurred_at} is written in. */
    public static LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }

    /** An instant, on that same wall clock — for comparing a run's clock against stored rows. */
    public static LocalDateTime wallClock(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZONE);
    }

    /**
     * The zone an event's own {@code start.timeZone} names, or {@link #ZONE} when Graph sent
     * nothing readable.
     *
     * <p>With the Prefer header above Graph answers {@code "Europe/Copenhagen"}, which is what
     * production returns on every event read on 2026-09-14. The fallback is for the shapes the
     * header does not govern — a Windows zone name such as {@code "Romance Standard Time"}
     * that {@link ZoneId#of} refuses — and falls to the same zone the header asked for rather
     * than to UTC, because a wrong zone by one or two hours is a far smaller error than one by
     * the whole offset.
     */
    public static ZoneId zoneOf(String graphTimeZone) {
        if (graphTimeZone == null || graphTimeZone.isBlank()) {
            return ZONE;
        }
        try {
            return ZoneId.of(graphTimeZone.trim());
        } catch (DateTimeException e) {
            return ZONE;
        }
    }
}
