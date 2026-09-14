package dk.trustworks.intranet.aggregates.crm.calendar.services;

import dk.trustworks.intranet.aggregates.crm.person.PersonNames;
import dk.trustworks.intranet.userservice.model.enums.StatusType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Trustworks people, by the name a client's Exchange writes on an invitation, with the
 * dates they were employed and the clients they have been placed at — the index behind the
 * colleague-at-client filter (decision D2, 2026-09-14; spec §4.1).
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
 * {@code [A-Za-z]+} split would shred them. The tokeniser and the two strict name rules
 * live in {@link PersonNames} and are shared with the people registry on purpose: two
 * spellings of "is this the same name" would drift, and the drift shows up as our own
 * consultants reappearing on an account as its client contacts.
 *
 * <p>A match on rule (a) is the user's tokens appearing as a <b>contiguous subsequence</b>
 * of the attendee's tokens, or the attendee's name reading as a <b>shortened form</b> of
 * the user's. It is emphatically NOT a {@code String.contains()}: that would make an
 * attendee called "Marianne Hansen" match a Trustworks user called "Anne Hansen" and
 * silently delete a real client contact from the account's relationship graph. There is a
 * test pinning exactly that case.
 *
 * <p>A user whose name is a single token is never matched at all. One token is one common
 * word; matching on it would eventually eat somebody.
 *
 * <h2>Rule (b): the client writes a middle name our user row does not carry</h2>
 * Rule (a) handles the client that writes MORE tokens contiguously and the client that
 * writes FEWER. It cannot handle the client that writes more tokens <b>in between</b> ours,
 * because the run is then no longer contiguous. That is the commonest shape of all on
 * production and it is defect D1 in the spec:
 *
 * <pre>
 *   "Sara Louise Vest (XSVES)"          our user row: Sara Vest              53 attendee rows
 *   "STMJ (Stephan Mosko Jensen)"       our user row: Stephan Jensen          8
 *   "Sebastian Bennett Frandsen (KEFM-KDS)"  our user row: Sebastian Frandsen 3
 *   "Mikal Yoo Jin Linderod Weber (XMWEB)"   our user row: Mikal Weber        1
 *   "Nina Schrøder Jakobsen (XNJAK)"    our user row: Nina Schøder Jakobsen    1  (our typo)
 * </pre>
 *
 * Sara Vest alone is 53 of 441 attendee rows, drawn on the Banedanmark page as both a
 * contract consultant on the left and a client contact on the right.
 *
 * <p>The rule is therefore widened to "first token, then last token, anything in between"
 * — but <b>only for an employee who has been placed at THIS client</b>. That confinement is
 * the whole safety argument. "Lars Peter Jensen" at a client where no Lars Jensen of ours
 * has ever worked is a client person and must stay one; the anchors-only rule would eat him
 * if it were offered to every employee. A placement is the evidence that our Lars Jensen
 * could plausibly be the person on that invitation at all. Note that the last of the five
 * rows above is matched on {@code nina … jakobsen} with no fuzzy matching whatsoever — the
 * {@code Schøder}/{@code Schrøder} typo in the {@code user} row is a separate one-line fix
 * (spec §12) and this rule does not depend on it being made.
 *
 * <h2>Order: (a) for everybody, and only then (b) for the placed</h2>
 * The strict rules are tried against every employee before the widened one is tried against
 * any. A looser rule must never take a match away from a stricter one that found a
 * different person — and running the two as separate passes rather than interleaved per
 * candidate is what guarantees it, independently of the longest-name-first order below.
 *
 * <h2>What this cannot do, and who covers it</h2>
 * Graph omits the display name entirely for some mailboxes — {@code mygx@novonordisk.com}
 * arrives with a name on ten attendee rows and as a bare address on twenty others — and a
 * bare address can never be name-matched. That hole is covered by the learned-e-mail table
 * {@code crm_colleague_client_email} (rule c): the first time a name match succeeds the
 * address is written down, and every later run drops it whatever name it arrives with. See
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

    /**
     * One (person, client) pair the person has been placed at — ever, with no date window
     * (spec §4.1).
     *
     * <p>Dates are deliberately absent. This is not the delivery exclusion, which asks
     * whether somebody was sitting at the client on a given day and needs the window for it
     * ({@link DeliveryContractIndex}); it is the far weaker question "is our Sara Vest
     * somebody who could plausibly be on an invitation at Banedanmark at all". An assignment
     * that ended in 2023 still answers that, and the employment test below still decides
     * what the meeting is.
     *
     * @param userUuid   the Trustworks employee
     * @param clientUuid the client they were assigned to, or whose domain issued them an
     *                   address we have already learned
     */
    record PlacementRow(String userUuid, String clientUuid) { }

    /**
     * Who an attendee name turned out to be, and which rule found them.
     *
     * <p>The rule matters to the caller and not only to this class: the nightly log has to
     * be able to say how often the widened rule (b) fired, because it is the one rule here
     * that can in principle take a real client person away and the count is the only way to
     * notice it starting to. See {@code CalendarSyncTally.colleagueByPlacement()}.
     *
     * @param userUuid    the matching employee
     * @param byPlacement true when rule (b) found them — first and last token with anything
     *                    between, allowed only because they have a placement at this client
     */
    record ColleagueMatch(String userUuid, boolean byPlacement) { }

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

    /**
     * {@code user_uuid → the clients they have been placed at}. The gate on rule (b), and
     * nothing else reads it.
     *
     * <p>It holds everybody, not only the people the name matcher carries, because it is
     * built before the single-token rule is applied and filtering it twice would only make
     * the two collections disagree.
     */
    private final Map<String, Set<String>> clientsByUuid;

    private ColleagueDirectory(List<Colleague> colleagues,
                               Map<String, List<StatusPoint>> statusesByUuid,
                               Map<String, Set<String>> clientsByUuid) {
        this.colleagues = colleagues;
        this.statusesByUuid = statusesByUuid;
        this.clientsByUuid = clientsByUuid;
    }

    /**
     * A directory with no placements, so rule (b) can never fire.
     *
     * <p>Kept as its own entry point because most callers — and every test of the strict
     * rules — have no business knowing that placements exist. It is not a shortcut: a
     * directory built this way answers exactly as it did before rule (b) was added.
     */
    static ColleagueDirectory of(Collection<ColleagueRow> rows) {
        return of(rows, List.of());
    }

    static ColleagueDirectory of(Collection<ColleagueRow> rows, Collection<PlacementRow> placements) {
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

        Map<String, Set<String>> clientsByUser = new LinkedHashMap<>();
        if (placements != null) {
            for (PlacementRow placement : placements) {
                if (placement == null || placement.userUuid() == null || placement.clientUuid() == null) {
                    continue;
                }
                clientsByUser.computeIfAbsent(placement.userUuid(), key -> new LinkedHashSet<>())
                        .add(placement.clientUuid());
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

        Map<String, Set<String>> frozenClients = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : clientsByUser.entrySet()) {
            frozenClients.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        return new ColleagueDirectory(
                List.copyOf(colleagues), Map.copyOf(statusesByUser), Map.copyOf(frozenClients));
    }

    /** Nobody known — nothing is a colleague, so nothing is filtered on the name rule. */
    static ColleagueDirectory empty() {
        return new ColleagueDirectory(List.of(), Map.of(), Map.of());
    }

    /**
     * Was this attendee display name one of ours on this date, by the strict rules alone?
     *
     * @return true when the name matches an employee who was employed on {@code date}
     */
    boolean isColleagueOn(String displayName, LocalDate date) {
        return colleagueUuidOn(displayName, date) != null;
    }

    /** The same question, with the client in hand, so rule (b) can apply. */
    boolean isColleagueOn(String displayName, LocalDate date, String clientUuid) {
        return colleagueUuidOn(displayName, date, clientUuid) != null;
    }

    /**
     * The strict rules only. Equivalent to passing a null client, and the form every caller
     * that has no client in hand should use.
     */
    String colleagueUuidOn(String displayName, LocalDate date) {
        return colleagueUuidOn(displayName, date, null);
    }

    /**
     * Is this attendee one of ours, asked about a meeting with a particular client?
     *
     * <p>The uuid is what makes the match worth more than a boolean: it is written into
     * {@code crm_colleague_client_email} alongside the address, so a later run that sees
     * only {@code mygx@novonordisk.com} with no display name still knows it is Malthe's.
     *
     * @param clientUuid the client the meeting is with; null means "not known", and rule
     *                   (b) is then skipped rather than applied to everybody
     * @return the matching employee's uuid, or null when the name is not one of ours or
     *         the person was not employed on that date
     */
    String colleagueUuidOn(String displayName, LocalDate date, String clientUuid) {
        ColleagueMatch match = colleagueOn(displayName, date, clientUuid);
        return match == null ? null : match.userUuid();
    }

    /**
     * The full answer: who, and by which rule.
     *
     * <p>Two passes, and the order is load-bearing. Pass one is the strict pair of rules
     * against every employee; pass two is the anchors-only rule against the employees placed
     * at this client. Interleaving them would let the widened rule claim an attendee that
     * the strict rule would have given to somebody else.
     *
     * <p>Neither pass breaks out when a name matches but the employment test fails. That is
     * the former-colleague-at-the-client case — the warmest contact an account can have —
     * and scanning on gives a differently-named employee the chance to match instead.
     *
     * @return the match, or null when nobody employed on that date answers to the name
     */
    ColleagueMatch colleagueOn(String displayName, LocalDate date, String clientUuid) {
        if (displayName == null || displayName.isBlank() || date == null) {
            return null;
        }
        List<String> attendeeTokens = tokensOf(displayName);
        if (attendeeTokens.isEmpty()) {
            return null;
        }

        // Rule a — strict, and offered to every employee.
        for (Colleague colleague : colleagues) {
            if (!containsSequence(attendeeTokens, colleague.tokens())
                    && !containsReductionOf(attendeeTokens, colleague.tokens())) {
                continue;
            }
            if (wasEmployedOn(colleague.statuses(), date)) {
                return new ColleagueMatch(colleague.userUuid(), false);
            }
            // Name matched but the person had left: this is a former colleague who now
            // works at the client, and they are a real client contact. Keep looking — a
            // different employee may still match — but never drop on their account.
        }

        // Rule b — first and last token with anything between, and ONLY for somebody who
        // has been placed at this client. Without the placement gate this rule matches
        // "Lars Peter Jensen" against our "Lars Jensen" and deletes a client person.
        if (clientUuid == null) {
            return null;
        }
        for (Colleague colleague : colleagues) {
            if (!isPlacedAt(colleague.userUuid(), clientUuid)
                    || !containsFirstAndLastOf(attendeeTokens, colleague.tokens())) {
                continue;
            }
            if (wasEmployedOn(colleague.statuses(), date)) {
                return new ColleagueMatch(colleague.userUuid(), true);
            }
            // Same reasoning as above: a placed colleague who has since left and stayed at
            // the client is that account's best contact, not one of us.
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

    /**
     * Has this person ever been placed at this client?
     *
     * <p>An unknown person or an unknown client answers false, which means rule (b) is not
     * offered — the same asymmetry the whole class is built on: not matching keeps an
     * attendee, and keeping a stale contact is a far smaller failure than deleting a real
     * one.
     */
    boolean isPlacedAt(String userUuid, String clientUuid) {
        if (userUuid == null || clientUuid == null) {
            return false;
        }
        Set<String> clients = clientsByUuid.get(userUuid);
        return clients != null && clients.contains(clientUuid);
    }

    /** How many people the directory can match. Logged once per run. */
    int size() {
        return colleagues.size();
    }

    /** How many (person, client) placements gate rule (b). Logged once per run. */
    int placementCount() {
        return clientsByUuid.values().stream().mapToInt(Set::size).sum();
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
     *
     * <p>Delegates to {@link PersonNames#tokens(String)}, which is the same algorithm this
     * class used to own: the people registry has to key a person the same way the colleague
     * filter refuses to, and two copies of a tokeniser drift. The wrapper stays because it
     * is the name the tests and the rest of this package know it by.
     */
    static List<String> tokensOf(String value) {
        return PersonNames.tokens(value);
    }

    /**
     * Does {@code needle} appear in {@code haystack} as a run of whole, adjacent tokens?
     *
     * <p>Whole tokens, so "anne" never matches inside "marianne". Adjacent, so
     * "Nicolas de Teilmann" does not match an attendee list that happens to contain a
     * Nicolas and, further along, a Teilmann. Delegates to
     * {@link PersonNames#containsSequence(List, List)}; the argument order is the same
     * there, attendee as the haystack.
     */
    static boolean containsSequence(List<String> haystack, List<String> needle) {
        return PersonNames.containsSequence(haystack, needle);
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
     * <p>Delegates to {@link PersonNames#isReductionOf(List, List)}, where the rule and the
     * reason it cannot re-open the "Marianne Hansen" hole are written out in full. Note the
     * argument order is the reverse of {@link #containsSequence}: the attendee is named
     * first here.
     *
     * <p>Measured against production it adds five addresses, all of them consultants on an
     * {@code ext}-prefixed client mailbox, each resolving to exactly one employee. One of
     * the five — {@code sae@appension.dk}, Sandra Holm Andersen — is then KEPT anyway by the
     * employment test, because she left on 2026-03-01 and the meeting is 2026-05-08: she
     * works at AP Pension now, and she is the most valuable contact that account has.
     */
    static boolean containsReductionOf(List<String> attendeeTokens, List<String> employeeTokens) {
        return PersonNames.isReductionOf(attendeeTokens, employeeTokens);
    }

    /**
     * Rule (b): does the attendee's name carry the employee's FIRST token and then, later,
     * their LAST token — with anything at all in between?
     *
     * <pre>
     *   [sara, louise, vest, xsves]      vs [sara, vest]       -> true   (sara … vest)
     *   [stmj, stephan, mosko, jensen]   vs [stephan, jensen]  -> true   (stephan … jensen)
     *   [nina, schrøder, jakobsen, xnjak] vs [nina, schøder, jakobsen] -> true (nina … jakobsen)
     *   [lars, peter, jensen]            vs [lars, jensen]     -> true   — and this is why
     *                                                                     the caller gates it
     *                                                                     on a placement
     * </pre>
     *
     * <p>The anchors have to be two DIFFERENT positions in the attendee's tokens, in order:
     * the last token is searched for strictly after the first was found, so a one-token
     * attendee name can never satisfy both ends at once. The first occurrence of the first
     * token is the one used, because it leaves the most room for the last token to follow —
     * an anchored-later start could only ever match less.
     *
     * <p><b>This rule is not safe on its own and is not offered on its own.</b> It has no
     * adjacency and no length constraint, so it will match any attendee who happens to share
     * a first name and a surname with one of ours. {@link #colleagueOn} only reaches it for
     * an employee with a placement at the client the meeting is with, and only after the
     * strict rules have found nobody.
     */
    static boolean containsFirstAndLastOf(List<String> attendeeTokens, List<String> employeeTokens) {
        if (employeeTokens.size() < 2 || attendeeTokens.size() < 2) {
            // A single-token employee name is never name-matched anywhere in this class,
            // and a single-token attendee cannot hold two anchors.
            return false;
        }
        String first = employeeTokens.get(0);
        String last = employeeTokens.get(employeeTokens.size() - 1);
        int start = attendeeTokens.indexOf(first);
        if (start < 0) {
            return false;
        }
        return attendeeTokens.subList(start + 1, attendeeTokens.size()).contains(last);
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
