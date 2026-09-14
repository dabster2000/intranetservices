package dk.trustworks.intranet.aggregates.crm.calendar.services;

import dk.trustworks.intranet.userservice.model.enums.StatusType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Trustworks people, by the name a client's Exchange writes on an invitation, with the
 * dates they were employed — the index behind the colleague-at-client filter (decision D2,
 * 2026-09-14).
 *
 * <h2>The problem this exists to solve</h2>
 * A consultant placed at a client is given a mailbox <b>at that client</b>.
 * {@code mygx@novonordisk.com} is Malthe Yde Andreasen; {@code qnte@novonordisk.com} is
 * Nicolas de Teilmann. To the domain join in the sync those addresses are indistinguishable
 * from the client's own CFO: novonordisk.com is a client domain, so they were being written
 * into {@code account_meeting_attendee} as EXTERNAL people the firm had met, and drawn in
 * the account's relationship graph as client contacts. The firm was being shown its own
 * consultants as its network into Novo Nordisk.
 *
 * <h2>Why employment is part of the rule and not an optimisation</h2>
 * A FORMER colleague who now works at the client is a genuine client contact, and one of
 * the best ones the firm has. Dropping them would delete exactly the relationship the
 * account page exists to surface. So the question is never "is this name one of ours" but
 * "was this name one of ours <b>on the day of that meeting</b>" — which is why the whole
 * status history is carried here rather than a set of current employees.
 *
 * <h2>The matching algorithm, and the bug it is written to avoid</h2>
 * Both sides are lower-cased and split on every character that is not a letter or a digit,
 * so {@code "QNTE (Nicolas De Teilmann)"} becomes {@code [qnte, nicolas, de, teilmann]} and
 * the user "Nicolas" + "de Teilmann" becomes {@code [nicolas, de, teilmann]}. Splitting on
 * {@link Character#isLetterOrDigit} rather than on whitespace keeps Danish letters and
 * hyphenated names whole — "Bjørn" and "Ellen-Marie" survive as tokens, where a
 * {@code [A-Za-z]+} split would shred them.
 *
 * <p>A match is the user's tokens appearing as a <b>contiguous subsequence</b> of the
 * attendee's tokens. It is emphatically NOT a {@code String.contains()}: that would make
 * an attendee called "Marianne Hansen" match a Trustworks user called "Anne Hansen" and
 * silently delete a real client contact from the account's relationship graph. There is a
 * test pinning exactly that case.
 *
 * <p>A user whose name is a single token is never matched at all. One token is one common
 * word; matching on it would eventually eat somebody.
 *
 * <h2>What this cannot do, and who covers it</h2>
 * Graph omits the display name entirely for some mailboxes — {@code mygx@novonordisk.com}
 * arrives with a name on ten attendee rows and as a bare address on twenty others — and a
 * bare address can never be name-matched. That hole is covered by the learned-e-mail table
 * {@code crm_colleague_client_email}: the first time a name match succeeds the address is
 * written down, and every later run drops it whatever name it arrives with. See
 * {@link CalendarFilterService}.
 *
 * <h2>Pure by construction</h2>
 * No {@code EntityManager}, no Panache. Rows in, answers out, so the fast-tier test can
 * hold the whole rule.
 */
final class ColleagueDirectory {

    /**
     * One {@code userstatus} row joined to its user.
     *
     * @param userUuid   {@code user.uuid} — needed because a name match is what teaches the
     *                   learned-e-mail table whose address it is
     * @param firstName  {@code user.firstname}
     * @param lastName   {@code user.lastname}
     * @param status     {@code userstatus.status}
     * @param statusDate {@code userstatus.statusdate} — the day that status took effect
     */
    record ColleagueRow(String userUuid, String firstName, String lastName,
                        StatusType status, LocalDate statusDate) { }

    /** One status change, stripped of everything the employment question does not use. */
    private record StatusPoint(StatusType status, LocalDate from) { }

    /** One person: their name as tokens, and the whole history of their employment. */
    private record Colleague(String userUuid, List<String> tokens, List<StatusPoint> statuses) { }

    /**
     * Ordered longest name first. When two employees' names both match an attendee — a
     * "Lars Hansen" and a "Lars Hansen Bak" in the same room's display name — the more
     * specific one is the one that should own the address, and iteration order is what
     * makes that deterministic rather than a matter of which row the database returned
     * first.
     */
    private final List<Colleague> colleagues;

    /**
     * The same employment histories, reachable by uuid instead of by name.
     *
     * <p>Needed because the address rule asks the employment question about somebody it did
     * NOT identify by name: {@code crm_colleague_client_email} already holds the uuid, and
     * without this map the address rule would have no way to ask "was this person still one
     * of ours on the day of that meeting" and would fall back to dropping them forever. See
     * {@link #wasEmployedOn(String, LocalDate)}.
     */
    private final Map<String, List<StatusPoint>> statusesByUuid;

    private ColleagueDirectory(List<Colleague> colleagues, Map<String, List<StatusPoint>> statusesByUuid) {
        this.colleagues = colleagues;
        this.statusesByUuid = statusesByUuid;
    }

    static ColleagueDirectory of(Collection<ColleagueRow> rows) {
        Map<String, List<StatusPoint>> statusesByUser = new LinkedHashMap<>();
        Map<String, List<String>> tokensByUser = new LinkedHashMap<>();

        if (rows != null) {
            for (ColleagueRow row : rows) {
                if (row == null || row.userUuid() == null || row.status() == null || row.statusDate() == null) {
                    // A status row without a status or a date says nothing about any date,
                    // and a row without a user belongs to nobody.
                    continue;
                }
                tokensByUser.computeIfAbsent(row.userUuid(), key -> tokensOf(fullNameOf(row)));
                // Every employment history is kept, including the single-token names the
                // NAME rule below refuses to match on. The ADDRESS rule asks about a uuid
                // rather than a name, and a MANUAL row in crm_colleague_client_email can
                // point at anybody — dropping their history here would silently turn the
                // address rule's employment question into "unknown", i.e. into "keep".
                statusesByUser.computeIfAbsent(row.userUuid(), key -> new ArrayList<>())
                        .add(new StatusPoint(row.status(), row.statusDate()));
            }
        }

        List<Colleague> colleagues = new ArrayList<>();
        for (Map.Entry<String, List<StatusPoint>> entry : statusesByUser.entrySet()) {
            List<String> tokens = tokensByUser.get(entry.getKey());
            if (tokens.size() < 2) {
                // Single-token names are never matched by name (see the class javadoc), so
                // the person is not offered to the name matcher at all. Their employment
                // history stays in statusesByUuid.
                continue;
            }
            colleagues.add(new Colleague(entry.getKey(), tokens, entry.getValue()));
        }
        colleagues.sort(Comparator.comparingInt((Colleague c) -> c.tokens().size()).reversed());
        return new ColleagueDirectory(List.copyOf(colleagues), Map.copyOf(statusesByUser));
    }

    /** Nobody known — nothing is a colleague, so nothing is filtered on the name rule. */
    static ColleagueDirectory empty() {
        return new ColleagueDirectory(List.of(), Map.of());
    }

    /**
     * Was this attendee display name one of ours on this date?
     *
     * @return true when the name matches an employee who was employed on {@code date}
     */
    boolean isColleagueOn(String displayName, LocalDate date) {
        return colleagueUuidOn(displayName, date) != null;
    }

    /**
     * The same question, answering with WHO.
     *
     * <p>The uuid is what makes the match worth more than a boolean: it is written into
     * {@code crm_colleague_client_email} alongside the address, so a later run that sees
     * only {@code mygx@novonordisk.com} with no display name still knows it is Malthe's.
     *
     * @return the matching employee's uuid, or null when the name is not one of ours or
     *         the person was not employed on that date
     */
    String colleagueUuidOn(String displayName, LocalDate date) {
        if (displayName == null || displayName.isBlank() || date == null) {
            return null;
        }
        List<String> attendeeTokens = tokensOf(displayName);
        if (attendeeTokens.isEmpty()) {
            return null;
        }
        for (Colleague colleague : colleagues) {
            if (!containsSequence(attendeeTokens, colleague.tokens())
                    && !containsReductionOf(attendeeTokens, colleague.tokens())) {
                continue;
            }
            if (wasEmployedOn(colleague.statuses(), date)) {
                return colleague.userUuid();
            }
            // Name matched but the person had left: this is a former colleague who now
            // works at the client, and they are a real client contact. Keep looking — a
            // different employee may still match — but never drop on their account.
        }
        return null;
    }

    /**
     * Was THIS person, identified by uuid rather than by name, one of ours on this date?
     *
     * <p>This is the employment half of the name rule, made available on its own so the
     * ADDRESS rule can ask the same question. It has to: {@code crm_colleague_client_email}
     * says whose an address is but says nothing about when, and a bare
     * {@code Set.contains} on that table would drop a former colleague's meetings for ever.
     * That is precisely the case the employment test exists to protect — somebody who left
     * Trustworks and stayed at the client is one of the warmest contacts the account has.
     * Production has the shape already: {@code trustworks-mh@aeldresagen.dk} belongs to a
     * colleague who left on 2026-04-01 and the address still appears on a meeting in June.
     *
     * <p><b>An unknown uuid answers false</b> — "not one of ours", so the attendee is kept.
     * A row whose person no longer exists in {@code user} cannot be shown to have been
     * employed, and the failure that matters here is deleting a real client contact, not
     * keeping a stale one.
     */
    boolean wasEmployedOn(String userUuid, LocalDate date) {
        if (userUuid == null || date == null) {
            return false;
        }
        List<StatusPoint> statuses = statusesByUuid.get(userUuid);
        return statuses != null && wasEmployedOn(statuses, date);
    }

    /** How many people the directory can match. Logged once per run. */
    int size() {
        return colleagues.size();
    }

    /**
     * First name and last name, with a null on either side treated as an empty string
     * rather than as the four letters {@code null} — a user row missing a surname must
     * tokenise to one token and be dropped by the single-token rule, not to two.
     */
    private static String fullNameOf(ColleagueRow row) {
        String first = row.firstName() == null ? "" : row.firstName();
        String last = row.lastName() == null ? "" : row.lastName();
        return first + " " + last;
    }

    /**
     * Lower-cased runs of letters and digits. Everything else — spaces, parentheses,
     * hyphens, commas, dots — is a separator, so {@code "QNTE (Nicolas De Teilmann)"} and
     * {@code "de Teilmann, Nicolas"} tokenise to the same words in different orders.
     */
    static List<String> tokensOf(String value) {
        if (value == null) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (Character.isLetterOrDigit(character)) {
                current.append(Character.toLowerCase(character));
            } else if (!current.isEmpty()) {
                tokens.add(current.toString());
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    /**
     * Does {@code needle} appear in {@code haystack} as a run of whole, adjacent tokens?
     *
     * <p>Whole tokens, so "anne" never matches inside "marianne". Adjacent, so
     * "Nicolas de Teilmann" does not match an attendee list that happens to contain a
     * Nicolas and, further along, a Teilmann.
     */
    static boolean containsSequence(List<String> haystack, List<String> needle) {
        if (needle.isEmpty() || needle.size() > haystack.size()) {
            return false;
        }
        outer:
        for (int start = 0; start + needle.size() <= haystack.size(); start++) {
            for (int offset = 0; offset < needle.size(); offset++) {
                if (!haystack.get(start + offset).equals(needle.get(offset))) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Does the attendee's name read as a SHORTENED form of this employee's — the same first
     * name and the same final surname, with one or more of the employee's middle names left
     * out?
     *
     * <p>{@link #containsSequence} only recognises an attendee name that contains the
     * employee's in full, which covers the client that writes MORE than we hold
     * ({@code "QNTE (Nicolas De Teilmann)"}) but not the one that writes LESS. Clients do
     * both. Dagrofa issued Nichlas Halberg Madsen the mailbox {@code extnim@dagrofa.dk} and
     * put {@code "Nichlas Madsen"} on it; our user row says {@code Nichlas} +
     * {@code Halberg Madsen}, three tokens against the attendee's two, so the containment
     * test could not match in that direction and ten meetings with our own consultant stayed
     * on the Dagrofa account as client contact.
     *
     * <p>The rule: some CONTIGUOUS window of the attendee's tokens must start on the
     * employee's first token, end on their last, and be an in-order subsequence of the
     * employee's tokens. A window rather than the whole name, so a client prefix survives
     * ({@code "NIM (Nichlas Madsen)"}); anchored on both ends, so it is a name shortened
     * rather than a name that merely shares a word.
     *
     * <p><b>It cannot re-open the substring hole.</b> "Marianne Hansen" against an employee
     * "Anne Hansen" still fails: every candidate window would have to START on {@code anne},
     * and {@code marianne} is a different token. Tokens are compared whole here exactly as
     * they are there.
     *
     * <p>Measured against production it adds five addresses, all of them consultants on an
     * {@code ext}-prefixed client mailbox, each resolving to exactly one employee. One of
     * the five — {@code sae@appension.dk}, Sandra Holm Andersen — is then KEPT anyway by the
     * employment test, because she left on 2026-03-01 and the meeting is 2026-05-08: she
     * works at AP Pension now, and she is the most valuable contact that account has.
     */
    static boolean containsReductionOf(List<String> attendeeTokens, List<String> employeeTokens) {
        if (employeeTokens.size() < 3 || attendeeTokens.size() < 2) {
            // Nothing to shorten: a two-token name has no middle to drop, and a one-token
            // window is the single-name match this class refuses everywhere else.
            return false;
        }
        String first = employeeTokens.get(0);
        String last = employeeTokens.get(employeeTokens.size() - 1);
        for (int start = 0; start < attendeeTokens.size(); start++) {
            if (!attendeeTokens.get(start).equals(first)) {
                continue;
            }
            for (int end = start + 1; end < attendeeTokens.size(); end++) {
                if (!attendeeTokens.get(end).equals(last)) {
                    continue;
                }
                if (end - start + 1 < employeeTokens.size()
                        && isOrderedSubsequence(attendeeTokens.subList(start, end + 1), employeeTokens)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Every token of {@code needle}, in order, somewhere in {@code haystack}. */
    private static boolean isOrderedSubsequence(List<String> needle, List<String> haystack) {
        int i = 0;
        for (String token : haystack) {
            if (i < needle.size() && needle.get(i).equals(token)) {
                i++;
            }
        }
        return i == needle.size();
    }

    /**
     * Employed on {@code date}?
     *
     * <p>This is {@code User.getUserStatus(LocalDate)} rewritten over plain rows, and it
     * has to stay that way: the status in effect is the one with the LATEST
     * {@code statusdate} on or before the date, and on a tie the NON-terminated row wins.
     * The tie-break is not cosmetic — a rehire and a termination are routinely filed on
     * the same day, and reading it the other way would mark somebody who came back as
     * gone. TERMINATED and PREBOARDING are both "not one of ours on that day": a
     * preboarder has an Intra user and a name but no Trustworks life yet, and anybody
     * writing to them is not writing to a colleague. No status row at or before the date
     * means the same thing — they did not exist to us yet.
     */
    private static boolean wasEmployedOn(List<StatusPoint> statuses, LocalDate date) {
        StatusType effective = statuses.stream()
                .filter(point -> !point.from().isAfter(date))
                .max(Comparator.comparing(StatusPoint::from)
                        .thenComparing(point -> point.status() == StatusType.TERMINATED ? 0 : 1))
                .map(StatusPoint::status)
                .orElse(null);
        return effective != null
                && effective != StatusType.TERMINATED
                && effective != StatusType.PREBOARDING;
    }
}
