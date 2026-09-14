package dk.trustworks.intranet.aggregates.crm.person;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * How warm a path to a person is, in five tiers, with one order used by every consumer
 * (spec §3.4, decision 4 of 2026-09-14).
 *
 * <h2>The problem this exists to solve</h2>
 * The account page had four sources and treated them as one graph. On production that is
 * 3,050 LinkedIn edges against 2 signals, so whoever happened to have connected on LinkedIn
 * outranked the colleague who actually sat in a meeting with the person last week. The tab,
 * the avatar stack, the Overview chips and the plan's coverage each sorted differently, so
 * the same account answered "who has the warmest path" three different ways depending on
 * where you asked.
 *
 * <h2>The order, and why it is the way round it is</h2>
 * <table>
 *   <caption>Tiers, warmest first</caption>
 *   <tr><th>Tier</th><th>Evidence</th><th>Within the tier</th></tr>
 *   <tr><td>1</td><td>{@code CLAIM} with strength &ge; 3, or {@code MET} within 90 days</td>
 *       <td>later date first; a claim's date is {@code claimedAt}</td></tr>
 *   <tr><td>2</td><td>{@code MET} older than 90 days, or {@code CLAIM} with strength 1–2</td>
 *       <td>later date first</td></tr>
 *   <tr><td>3</td><td>the person is {@code ALUMNI}, whatever the edges</td>
 *       <td>left most recently first</td></tr>
 *   <tr><td>4</td><td>{@code KNOWS} or {@code HEARD}</td>
 *       <td>dated {@code HEARD} before undated {@code KNOWS}, then date</td></tr>
 *   <tr><td>5</td><td>{@code CONNECTED} only</td>
 *       <td>later {@code connectedOn} first, undated last</td></tr>
 * </table>
 * LinkedIn is tier 5 by construction: decision 4 says a connection is a secondary hint and
 * must never be presented as "knows them" or ranked above something a person did or said. A
 * source this class does not recognise lands in tier 5 for the same reason — a new edge kind
 * appearing in the data before the code that knows it must not be able to outrank a meeting.
 *
 * <h2>A person's tier, and the alumni clamp</h2>
 * A person's tier is their <b>best</b> edge's tier, and an {@code ALUMNI} person is
 * {@code min(3, bestEdgeTier)} — a former colleague who now works at the client is one of the
 * warmest contacts the firm has (defect D4), so they can never rank below tier 3 however
 * quiet their edges are, and a warm meeting or claim still lifts them above it. A person with
 * no edges at all is tier 5, or tier 3 when they are alumni: being alumni IS the evidence.
 *
 * <h2>One order, two languages</h2>
 * This is the mirror of {@code trustworks-intranet-v2/src/lib/crm/relationshipWarmth.ts}.
 * The tier numbers, the 90-day boundary, the strength-3 boundary and the tie-breaks are the
 * same on both sides on purpose: the backend sorts the table's default order and the frontend
 * re-sorts after an optimistic claim, and a reader must not see the row jump. Change one and
 * you must change the other.
 *
 * <h2>Pure by construction</h2>
 * No CDI, no {@code EntityManager}, and <b>no clock</b> — {@code today} is always passed in,
 * so a test can hold the whole rule and so that a rebuild judging an eighteen-month-old
 * meeting does it against the date it means to.
 */
public final class RelationshipWarmth {

    /** Warmest. Nothing can beat it, which is what lets {@link #personTier} stop early. */
    public static final int WARMEST_TIER = 1;

    /** The ceiling an {@code ALUMNI} person can never fall below. */
    public static final int ALUMNI_TIER = 3;

    /** Coldest, and the tier of a person with no evidence at all. */
    public static final int COLDEST_TIER = 5;

    /**
     * A meeting this recent is "real contact". The same 90 days the portfolio's quiet rule
     * uses, so the account that is going quiet and the person who has gone quiet mean the
     * same thing. The boundary is inclusive: exactly 90 days ago is still tier 1.
     */
    public static final int RECENT_DAYS = 90;

    /** A claim at least this strong is a door, not an acquaintance: "good working relationship". */
    public static final int STRONG_CLAIM = 3;

    /**
     * The last-resort tie-break, and the order decision 12 ranks the Overview chips in:
     * claim, met, said, LinkedIn. It only ever decides between two edges that are in the same
     * tier AND carry the same date, which is rare enough that its job is simply to make the
     * sort deterministic — a table whose rows swap places between two renders of identical
     * data reads as a bug.
     */
    public static final List<String> RELATION_SOURCE_ORDER = List.of("MET", "CLAIM", "HEARD", "KNOWS", "CONNECTED");

    /**
     * The three things this class needs to know about an edge, and nothing else.
     *
     * <p>An interface rather than a record so the service can let its own row or DTO answer
     * these directly and sort with {@link #byWarmth(LocalDate)} without copying, while a test
     * builds one in a line with {@link #edge(String, Integer, LocalDate)}. Deliberately blind
     * to who the edge is between: warmth is a property of the evidence, not of the people.
     */
    public interface EdgeLike {

        /** {@code MET} | {@code CLAIM} | {@code KNOWS} | {@code HEARD} | {@code CONNECTED}. */
        String source();

        /** 1–4 for a {@code CLAIM}, null for every other source. */
        Integer strength();

        /**
         * The one date this edge is judged on: {@code lastMet} for {@code MET},
         * {@code claimedAt} for {@code CLAIM}, {@code heardOn} for {@code HEARD},
         * {@code connectedOn} for {@code CONNECTED}, and null for {@code KNOWS}, which a
         * signal never dates. Null is not an error — it sorts last within its tier.
         */
        LocalDate date();
    }

    private record SimpleEdge(String source, Integer strength, LocalDate date) implements EdgeLike { }

    private RelationshipWarmth() {
    }

    /** An {@link EdgeLike} out of three values, for callers that have no row to hand. */
    public static EdgeLike edge(String source, Integer strength, LocalDate date) {
        return new SimpleEdge(source, strength, date);
    }

    /**
     * The tier of one piece of evidence, with the alumni clamp already applied.
     *
     * <p>Clamping per edge rather than only per person is safe and deliberate: {@code min} of
     * {@code min(3, x)} over a person's edges is the same number as {@code min(3, min x)}, so
     * a caller that ranks a single edge and a caller that ranks a person cannot disagree.
     *
     * @param source   the edge's source; null or unrecognised is tier 5
     * @param strength the claim strength, or null
     * @param date     the edge's date, or null
     * @param today    the day the question is being asked about — never {@code LocalDate.now()}
     *                 picked up inside a loop
     * @param alumni   whether the PERSON this edge points at is a former colleague
     */
    public static int tierOf(String source, Integer strength, LocalDate date, LocalDate today, boolean alumni) {
        int tier = edgeTier(source, strength, date, today);
        return alumni ? Math.min(ALUMNI_TIER, tier) : tier;
    }

    /**
     * The person's tier: the best of their edges, clamped to {@link #ALUMNI_TIER} when they
     * are a former colleague.
     *
     * <p>No edges is {@link #COLDEST_TIER}, not an error and not "unknown" — the registry
     * keeps a person whose sources have all gone quiet (a claim or a star must survive a
     * disabled TrustLink alias), and such a person belongs at the bottom of the table rather
     * than missing from it.
     *
     * @param edges  may be null, may contain nulls, may be empty
     * @param alumni whether the person is {@code ALUMNI}
     * @param today  the day the 90-day window is measured back from
     */
    public static int personTier(Collection<EdgeLike> edges, boolean alumni, LocalDate today) {
        int best = COLDEST_TIER;
        if (edges != null) {
            for (EdgeLike edge : edges) {
                if (edge == null) {
                    continue;
                }
                best = Math.min(best, edgeTier(edge.source(), edge.strength(), edge.date(), today));
                if (best == WARMEST_TIER) {
                    break;
                }
            }
        }
        return alumni ? Math.min(ALUMNI_TIER, best) : best;
    }

    /**
     * Warmest edge first: tier, then later date first with undated last, then
     * {@link #RELATION_SOURCE_ORDER}.
     *
     * <p>"Undated last" is one rule doing two jobs from the table above: it is what puts a
     * dated {@code HEARD} ahead of an undated {@code KNOWS} in tier 4, and what puts a
     * connection with no {@code connected_on} — 43 of the 1,876 production rows — behind every
     * dated one in tier 5.
     *
     * <p>This compares EDGES, so it never applies the alumni clamp: alumni is a property of
     * the person, every edge of that person would be clamped identically, and tier 3's own
     * ordering ("left most recently first") is over {@code alumniLeftOn}, which is not on an
     * edge at all. A caller ranking PEOPLE sorts on {@link #personTier} and then on that date.
     *
     * @param today the day the 90-day window is measured back from
     */
    public static Comparator<EdgeLike> byWarmth(LocalDate today) {
        Comparator<LocalDate> laterFirstUndatedLast = Comparator.nullsLast(Comparator.<LocalDate>reverseOrder());
        return Comparator
                .comparingInt((EdgeLike edge) -> edge == null
                        ? COLDEST_TIER
                        : edgeTier(edge.source(), edge.strength(), edge.date(), today))
                .thenComparingInt(edge -> meetingBeforeClaimInTierTwo(edge, today))
                .thenComparing(RelationshipWarmth::dateOf, laterFirstUndatedLast)
                .thenComparingInt(RelationshipWarmth::sourceRank);
    }

    /**
     * Inside tier 2 only, a {@code MET} edge ranks ahead of a {@code CLAIM} edge whatever their
     * dates say.
     *
     * <p>Tier 2 — "a meeting older than 90 days, or a claim of 1–2" — is the one place where
     * §3.4 puts two different KINDS of date side by side and then asks for "later date first".
     * They are not the same fact: {@code claimedAt} records the day somebody typed a sentence,
     * {@code lastMet} records the day they were in a room together. Read literally, a "we have
     * met once" claim filed this morning would outrank a real meeting from last year — which is
     * exactly what §10.12 refuses: <em>"a claimed person's colleague ranks above a met-in-2025
     * one ONLY if strength &gt;= 3"</em>. A strong claim still wins; it is tier 1 and never
     * reaches this comparison.
     *
     * <p>Returned as a sort key rather than a pairwise rule because only {@code MET} and
     * {@code CLAIM} can ever land in tier 2 — {@code KNOWS}/{@code HEARD} are tier 4 and
     * {@code CONNECTED} is tier 5 — so every edge in every other tier answers 0 and the step is
     * a no-op there. Tier 1 keeps date-first: a trusted claim and a meeting inside 90 days are
     * genuine peers. This mirrors {@code compareRelationEdges} in
     * {@code src/lib/crm/relationshipWarmth.ts}; the two must not drift.
     */
    private static int meetingBeforeClaimInTierTwo(EdgeLike edge, LocalDate today) {
        if (edge == null) return 0;
        if (edgeTier(edge.source(), edge.strength(), edge.date(), today) != 2) return 0;
        return "CLAIM".equals(normalise(edge.source())) ? 1 : 0;
    }

    /** The tier of the evidence alone, before anything is known about the person. */
    private static int edgeTier(String source, Integer strength, LocalDate date, LocalDate today) {
        return switch (normalise(source)) {
            case "CLAIM" -> strength != null && strength >= STRONG_CLAIM ? 1 : 2;
            case "MET" -> isRecent(date, today) ? 1 : 2;
            case "KNOWS", "HEARD" -> 4;
            // CONNECTED, and every source this code does not know about: a LinkedIn
            // connection is a hint (decision 4) and an unknown edge kind has earned nothing.
            default -> COLDEST_TIER;
        };
    }

    /**
     * Within the window, counting back from {@code today}. A meeting in the future — an
     * invitation already accepted — counts as recent, which is the honest answer to "when did
     * we last really talk to them"; an undated meeting cannot be shown to be recent and is
     * therefore not.
     */
    private static boolean isRecent(LocalDate date, LocalDate today) {
        return date != null && today != null && ChronoUnit.DAYS.between(date, today) <= RECENT_DAYS;
    }

    private static LocalDate dateOf(EdgeLike edge) {
        return edge == null ? null : edge.date();
    }

    private static int sourceRank(EdgeLike edge) {
        int index = RELATION_SOURCE_ORDER.indexOf(normalise(edge == null ? null : edge.source()));
        return index < 0 ? RELATION_SOURCE_ORDER.size() : index;
    }

    /**
     * {@link Locale#ROOT}, never the default locale: on a machine booted in a Turkish locale
     * {@code "signal".toUpperCase()} is {@code "SİGNAL"} and every source would fall through
     * to tier 5 without a single error being logged.
     */
    private static String normalise(String source) {
        return source == null ? "" : source.trim().toUpperCase(Locale.ROOT);
    }
}
