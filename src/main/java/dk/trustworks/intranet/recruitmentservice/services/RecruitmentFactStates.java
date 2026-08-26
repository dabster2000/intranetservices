package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentFactVocabulary;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentFactVocabulary.FactField;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fact state machine as pure functions (Interview Room design spec
 * 2026-08-26 §4.3) — shared by the per-candidate derivation, the
 * {@code RecruitmentFactStateProjector} and its rebuild, so the three can
 * never disagree. Every vocabulary key resolves to exactly one state per
 * candidate, computed from the events and never persisted as-is:
 * <ul>
 *   <li>{@code UNKNOWN} — no NOTE_ADDED with this field.</li>
 *   <li>{@code ASKED} — the newest note carries {@code payload.outcome='ASKED'}:
 *       someone raised it and got nothing usable. Records that the question
 *       was spent, so the next interviewer tries differently.</li>
 *   <li>{@code STATED} — the newest note carries a value.</li>
 *   <li>{@code CONFIRMED} — the newest value-bearing note carries
 *       {@code payload.confirmed=true}. The only state an offer should
 *       rely on. Confirmation never goes stale.</li>
 *   <li>{@code STALE} — was STATED and the newest value is older than the
 *       group's freshness window (Competition 14 d · Timing 30 ·
 *       Compensation 60 · References/Practicalities never).</li>
 * </ul>
 */
public final class RecruitmentFactStates {

    private RecruitmentFactStates() {
    }

    /** Effective (read-time) states — the persisted subset is ASKED/STATED/CONFIRMED. */
    public enum State {
        UNKNOWN, ASKED, STATED, CONFIRMED, STALE
    }

    /**
     * One fact-bearing NOTE_ADDED, reduced to the structural parts the
     * state machine reads. {@code occurredAt} is UTC.
     */
    public record FactNote(long seq, String field, LocalDateTime occurredAt,
                           boolean asked, boolean confirmed) {
    }

    /** The persisted projection value for one (candidate, field). */
    public record Persisted(String state, long lastValueEventSeq, LocalDateTime lastStatedAt) {
    }

    /**
     * Fold fact notes (any order) into the persisted state per field —
     * exactly what the projector maintains incrementally and the rebuild
     * recomputes. The NEWEST note (highest seq) decides, except that an
     * ASKED note never downgrades an existing value: "asked again, still
     * nothing" must not erase what a previous round did get.
     */
    public static Map<String, Persisted> fold(List<FactNote> notes) {
        Map<String, Persisted> result = new LinkedHashMap<>();
        notes.stream()
                .filter(n -> RecruitmentFactVocabulary.isKnown(n.field()))
                .sorted((a, b) -> Long.compare(a.seq(), b.seq()))
                .forEach(n -> result.merge(n.field(), toPersisted(n),
                        (existing, incoming) -> apply(existing, n)));
        return result;
    }

    /** Apply one newer note to an existing persisted state (the projector's upsert rule). */
    public static Persisted apply(Persisted existing, FactNote note) {
        if (existing == null) {
            return toPersisted(note);
        }
        if (note.asked()) {
            // The question was spent; an existing value survives.
            return "ASKED".equals(existing.state()) || existing.lastStatedAt() == null
                    ? new Persisted("ASKED", note.seq(), null)
                    : new Persisted(existing.state(), note.seq(), existing.lastStatedAt());
        }
        return toPersisted(note);
    }

    private static Persisted toPersisted(FactNote note) {
        if (note.asked()) {
            return new Persisted("ASKED", note.seq(), null);
        }
        return new Persisted(note.confirmed() ? "CONFIRMED" : "STATED",
                note.seq(), note.occurredAt());
    }

    /**
     * The read-time state: applies the group's freshness window to a
     * persisted STATED value ({@code CONFIRMED} and {@code ASKED} never go
     * stale — a confirmation is settled and a spent question cannot rot).
     *
     * @param nowUtc the read-time clock, UTC — passed in so the seam is testable
     */
    public static State effective(FactField field, Persisted persisted, LocalDateTime nowUtc) {
        if (persisted == null) {
            return State.UNKNOWN;
        }
        return switch (persisted.state()) {
            case "ASKED" -> State.ASKED;
            case "CONFIRMED" -> State.CONFIRMED;
            case "STATED" -> isStale(field, persisted.lastStatedAt(), nowUtc)
                    ? State.STALE
                    : State.STATED;
            default -> State.UNKNOWN;
        };
    }

    private static boolean isStale(FactField field, LocalDateTime statedAt, LocalDateTime nowUtc) {
        Integer window = field.group().freshnessDays();
        return window != null && statedAt != null
                && statedAt.plusDays(window).isBefore(nowUtc);
    }
}
