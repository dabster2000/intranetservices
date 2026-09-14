package dk.trustworks.intranet.aggregates.crm.slack.services;

import dk.trustworks.intranet.aggregates.crm.account.dto.PersonDTO;
import dk.trustworks.intranet.aggregates.crm.account.services.AccountService;
import dk.trustworks.intranet.aggregates.crm.calendar.services.CalendarSuggestionService;
import dk.trustworks.intranet.aggregates.crm.slack.dto.SlackSuggestionDTO;
import dk.trustworks.intranet.aggregates.crm.slack.dto.SlackSuggestionDecisionRequest;
import dk.trustworks.intranet.aggregates.crm.slack.model.SlackSourceChannel;
import dk.trustworks.intranet.aggregates.crm.slack.model.SlackUnmatchedCompany;
import dk.trustworks.intranet.aggregates.crm.slack.model.SlackUnmatchedCompanySighting;
import dk.trustworks.intranet.aggregates.crm.slack.model.SlackUnmatchedCompanySightingAuthor;
import dk.trustworks.intranet.aggregates.crm.slack.model.enums.UnmatchedCompanyStatus;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.services.ClientService;
import dk.trustworks.intranet.domain.user.entity.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.HashMap;
import java.util.HashSet;
import dk.trustworks.intranet.aggregates.crm.slack.dto.SlackDigestContent;

/**
 * "Heard in Slack" — the companies colleagues keep talking about in general channels that
 * Intra does not know (spec §4.5, §5.3, §6.2).
 *
 * <p>The sibling of {@link CalendarSuggestionService}, and deliberately its mirror method
 * for method: a hint arrives from the nightly read, the numbers are recomputed from a
 * ledger, the panel offers the ones nobody has decided on, and there are three things
 * somebody can do about each. The calendar lane learns a company from a DOMAIN it could not
 * attribute; this one learns it from a NAME the model read out of a sentence. Everything
 * below that differs, differs because of that one sentence.
 *
 * <p><b>Counts are recomputed, never incremented.</b> The lane re-reads a lookback window
 * on every run, so an incremented counter would report one conversation as a fortnight of
 * them. {@link #record} writes an idempotent ledger row per (name, channel, day) and
 * {@link #refreshAggregates()} derives the numbers from it once per run — the V601 reasoning,
 * unchanged.
 *
 * <p><b>A LINKED row is an ALIAS, not an archived decision.</b> Linking a hint is the whole
 * mechanism by which the matcher learns the name (D4): from the next run it resolves
 * straight to the client and the mentions land on the account. That is why the sweep at the
 * end of {@link #refreshAggregates()} keeps LINKED rows as well as IGNORED ones, where the
 * calendar lane keeps only IGNORED. There, LINK writes a {@code client_domain} row and the
 * aggregate is just a record of the decision; here the row IS the alias, and deleting it
 * because its sightings aged out would quietly un-teach the name.
 *
 * <p><b>What is never stored, and never logged:</b> no line of Slack, no headline, no raw
 * Slack id. The company names are model output, so they do not appear in an INFO line
 * either — the decisions log ids, counts and the actor.
 */
@JBossLog
@ApplicationScoped
public class SlackUnmatchedCompanyService {

    /**
     * How long a name nobody has talked about is kept. The same window the calendar lane
     * keeps a domain for, and for the same reason: a company last mentioned more than a
     * year ago is not somebody's live relationship, and the row would be data held for no
     * purpose.
     */
    public static final int RETENTION_MONTHS = 12;

    /** "3 mentions since June · Tommy, Lukas" — the row shows a handful of names, not a list. */
    private static final int MAX_PEOPLE_SHOWN = 5;

    /** The panel is a nudge, not an inbox. */
    public static final int DEFAULT_LIMIT = 25;
    public static final int MAX_LIMIT = 200;

    /**
     * The width of the primary key. A longer name would abort the whole day's transaction
     * on a {@code data too long} rather than lose the one hint it came in on, so the guard
     * is here and not left to the database.
     */
    private static final int MAX_NAME_CHARS = 190;

    @Inject
    EntityManager em;

    @Inject
    ClientService clientService;

    /**
     * The light company-creation path. It lives on the calendar lane because that is where
     * it was written first, and it is injected rather than copied: two implementations of
     * "add the company somebody just saw" would be two duplicate-name rules, and the
     * duplicates are exactly what both panels exist to stop making.
     */
    @Inject
    CalendarSuggestionService prospects;

    @Inject
    AccountSlackFeatureFlag featureFlag;

    // ------------------------------------------------------------------------
    // Write — from the sync
    // ------------------------------------------------------------------------

    /**
     * One channel-day's worth of sightings.
     *
     * <p>What the extractor could not attribute to any account: the company name it read,
     * which channel and which day it was heard on, how many cited lines named it, and which
     * colleagues wrote them.
     *
     * @param nameKey     lower-cased and whitespace-collapsed; the identity of the hint
     * @param displayName the spelling as heard, kept only for the panel to show
     * @param channelId   the source channel — its NAME lives on the configuration row, not here
     * @param sightedOn   the day, in the lane's own Copenhagen wall clock
     * @param mentionCount cited lines naming this company in this channel that day
     * @param authorUuids  the colleagues behind those lines; a Slack id that mapped to
     *                     nobody has already been dropped by the caller, never stored raw
     * @param permalink    where to go and read the original in Slack; may be null
     */
    public record UnmatchedCompanySighting(String nameKey, String displayName, String channelId,
                                           LocalDate sightedOn, int mentionCount,
                                           Collection<String> authorUuids, String permalink,
                                           String signalType, String headline) { }

    /**
     * Writes one read's sightings.
     *
     * <p>Called inside the day's own short write transaction, alongside the mentions that
     * day did match — never between a Slack call and the next one.
     *
     * <p>A name somebody has already decided on is still recorded. An IGNORE is a deny-list,
     * not a deletion: the sightings keep accruing quietly so the decision can be reversed
     * with the numbers intact, and the read filters on status.
     */
    public void record(Collection<UnmatchedCompanySighting> sightings, LocalDateTime now) {
        if (sightings == null || sightings.isEmpty()) {
            return;
        }
        for (UnmatchedCompanySighting sighting : sightings) {
            if (sighting.nameKey() == null || sighting.nameKey().isBlank()
                    || sighting.channelId() == null || sighting.sightedOn() == null) {
                continue;
            }
            if (sighting.nameKey().length() > MAX_NAME_CHARS) {
                // Counted, never spelled out: the name is model output.
                log.warnf("Slack hints: dropped a sighting whose name key was %d characters",
                        sighting.nameKey().length());
                continue;
            }
            ensureCompany(sighting, now);

            String uuid = sightingUuid(sighting.nameKey(), sighting.channelId(), sighting.sightedOn());
            SlackUnmatchedCompanySighting row = SlackUnmatchedCompanySighting.findById(uuid);
            if (row == null) {
                row = new SlackUnmatchedCompanySighting();
                row.setUuid(uuid);
                row.setNameKey(sighting.nameKey());
                row.setChannelId(sighting.channelId());
                row.setSightedOn(sighting.sightedOn());
            }
            Set<String> authors = distinct(sighting.authorUuids());
            row.setMentionCount(Math.max(sighting.mentionCount(), 0));
            row.setSignalType(SlackDigestContent.signalTypeOf(sighting.signalType()));
            row.setHeadline(sighting.headline());
            row.setAuthorCount(authors.size());
            if (sighting.permalink() != null) {
                row.setPermalink(sighting.permalink());
            }
            row.persist();

            // Replaced wholesale, as the digest lane replaces its participants: the set is
            // small, and a colleague whose Slack link was fixed since must not stay missing
            // from a day that has already been read.
            SlackUnmatchedCompanySightingAuthor.delete("sightingUuid", uuid);
            for (String userUuid : authors) {
                SlackUnmatchedCompanySightingAuthor author = new SlackUnmatchedCompanySightingAuthor();
                author.setUuid(UUID.randomUUID().toString());
                author.setSightingUuid(uuid);
                author.setUserUuid(userUuid);
                author.persist();
            }
        }
    }

    /**
     * The parent row on first sight, and only then.
     *
     * <p>{@link #refreshAggregates()} cannot create these the way the calendar lane's
     * recompute creates its domains, because the ledger has no name in it to create them
     * from: a sighting is a key, a channel and a day. The spelling a human should read
     * exists only at the moment the name is heard, so it is captured here and the recompute
     * never touches {@code display_name}.
     *
     * <p>A row that already exists keeps everything it has — most of all its status, which
     * is somebody's decision and not this run's business.
     */
    private void ensureCompany(UnmatchedCompanySighting sighting, LocalDateTime now) {
        SlackUnmatchedCompany company = SlackUnmatchedCompany.findById(sighting.nameKey());
        if (company != null) {
            return;
        }
        String displayName = sighting.displayName() == null || sighting.displayName().isBlank()
                ? sighting.nameKey()
                : sighting.displayName().trim();
        company = new SlackUnmatchedCompany();
        company.setNameKey(sighting.nameKey());
        company.setDisplayName(displayName.length() > MAX_NAME_CHARS
                ? displayName.substring(0, MAX_NAME_CHARS)
                : displayName);
        company.setStatus(UnmatchedCompanyStatus.NEW);
        company.setCreatedAt(now);
        company.setUpdatedAt(now);
        company.persist();
    }

    /**
     * Rebuilds every company row from the ledger, and forgets what is older than the
     * retention window.
     *
     * <p>One pass at the end of a run rather than a running total: see the class javadoc for
     * why a counter would be wrong. The purge goes first so the numbers are computed over
     * what is actually kept — the sighting's authors go with it on the foreign key's
     * cascade, which is why there is one delete here and not two.
     *
     * <p>The aggregates need two passes over the ledger and not one. Joining the authors in
     * fans each sighting out into a row per colleague, and summing {@code mention_count}
     * across that would multiply a day's mentions by the number of people who wrote them.
     * So the sums and the distinct-colleague count are asked for separately and stitched
     * together here.
     */
    @Transactional
    public void refreshAggregates() {
        LocalDate today = LocalDate.now();
        LocalDate cutoff = today.minusMonths(RETENTION_MONTHS);
        LocalDate ninetyDaysAgo = today.minusDays(90);

        long purged = SlackUnmatchedCompanySighting.delete("sightedOn < ?1", cutoff);
        if (purged > 0) {
            log.infof("Slack hints: purged %d sightings older than %s", purged, cutoff);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                select name_key,
                       sum(mention_count)                                                   as mentions_total,
                       sum(case when sighted_on >= :recent then mention_count else 0 end)   as mentions_90d,
                       count(distinct channel_id)                                           as channels_count,
                       min(sighted_on)                                                      as first_seen,
                       max(sighted_on)                                                      as last_seen
                  from slack_unmatched_company_sighting
                 group by name_key
                """)
                .setParameter("recent", ninetyDaysAgo.toString())
                .getResultList();

        Map<String, Integer> colleaguesByName = colleagueCounts();

        LocalDateTime now = LocalDateTime.now();
        Set<String> seen = new LinkedHashSet<>();
        for (Object[] row : rows) {
            String nameKey = String.valueOf(row[0]);
            seen.add(nameKey);
            SlackUnmatchedCompany aggregate = SlackUnmatchedCompany.findById(nameKey);
            if (aggregate == null) {
                // Only reachable if a sighting outlived its parent row. The key is the only
                // spelling left, and a hint in lower case beats a hint that vanished.
                aggregate = new SlackUnmatchedCompany();
                aggregate.setNameKey(nameKey);
                aggregate.setDisplayName(nameKey);
                aggregate.setStatus(UnmatchedCompanyStatus.NEW);
                aggregate.setCreatedAt(now);
            }
            aggregate.setMentionsTotal(intOf(row[1]));
            aggregate.setMentions90d(intOf(row[2]));
            aggregate.setChannelsCount(intOf(row[3]));
            aggregate.setPeopleCount(colleaguesByName.getOrDefault(nameKey, 0));
            aggregate.setFirstSeen(AccountService.toLocalDate(row[4]));
            aggregate.setLastSeen(AccountService.toLocalDate(row[5]));
            // The calendar lane has no such column and so has no write to mirror. It is NOT
            // NULL with no default, so leaving it unset fails the very first insert.
            aggregate.setUpdatedAt(now);
            aggregate.persist();
        }

        // A name whose every sighting aged out. The row goes with them — except when
        // somebody decided about it, because an IGNORE is worth keeping past the data it was
        // made about, and a LINK is the alias that keeps the name off this panel at all.
        for (SlackUnmatchedCompany aggregate : SlackUnmatchedCompany.<SlackUnmatchedCompany>listAll()) {
            if (!seen.contains(aggregate.getNameKey())
                    && aggregate.getStatus() == UnmatchedCompanyStatus.NEW) {
                aggregate.delete();
            }
        }
    }

    /** {@code name → distinct colleagues who wrote about it}, across every sighting it has. */
    private Map<String, Integer> colleagueCounts() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                select s.name_key, count(distinct a.user_uuid) as people_count
                  from slack_unmatched_company_sighting s
                  join slack_unmatched_company_sighting_author a on a.sighting_uuid = s.uuid
                 group by s.name_key
                """)
                .getResultList();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Object[] row : rows) {
            counts.put(String.valueOf(row[0]), intOf(row[1]));
        }
        return counts;
    }

    // ------------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------------

    /**
     * Names nobody has decided on that are worth deciding on, the ones people talk about
     * most first.
     *
     * <p>Ordered by recent mentions and then by how many colleagues are involved: a company
     * three people mentioned last month is a better suggestion than one somebody named nine
     * times two years ago.
     *
     * <h2>What this now leaves out, and why</h2>
     * The first two production runs filled this panel with names nobody could act on. Of
     * nine hints on 2026-09-14, four were companies Intra already has — {@code NN} is NOVO
     * NORDISK A/S, which has a linked account space in this very feature; {@code KDS} is
     * Klimadatastyrelsen; {@code Devoteam} exists twice, and the duplicate row is what made
     * its alias ambiguous; {@code Københavns Kommune} is "Københavns Kommune KFF". Every one
     * of the nine had been heard exactly once, by one colleague, in one channel. A panel
     * asking somebody to judge nine things, four of which are wrong and none of which is a
     * pattern, does not get used.
     *
     * <p>So a hint is offered when it is ACTIONABLE, which is one of two things:
     * <ul>
     *   <li>{@link #likelyClient} found a company we already have. Then the row is worth
     *       one click whatever its count: linking it teaches the alias map, and the name
     *       resolves on every future run instead of coming back here for ever.</li>
     *   <li>It is {@code corroborated} — heard on more than one day, or by more than one
     *       colleague, or in more than one channel. One person naming a company once is
     *       not yet news.</li>
     * </ul>
     *
     * <p>Everything else keeps accumulating, silently, and appears the moment it crosses
     * either bar. <b>Nothing is deleted and nothing is decided on anybody's behalf</b> — the
     * row, its sightings and its authors all stay in the table, and the count of what is
     * held back is logged so an empty panel is never a mystery.
     *
     * <p>The cost of being wrong here is asymmetric, which is why the bars are low and the
     * client match is conservative: a hint held back one more week costs a week, and a hint
     * wrongly auto-linked files another company's news on a client's page where somebody
     * will read it as fact.
     */
    public List<SlackSuggestionDTO> suggestions(int limit) {
        int capped = Math.min(Math.max(limit, 1), MAX_LIMIT);
        // Read past the cap: the actionable test below runs in Java (it needs the client
        // list), so paging to `capped` first would let held-back rows eat the page and
        // hide offerable ones behind them.
        List<SlackUnmatchedCompany> candidates = SlackUnmatchedCompany
                .find("status = ?1 order by mentions90d desc, peopleCount desc, mentionsTotal desc, nameKey",
                        UnmatchedCompanyStatus.NEW)
                .page(0, MAX_LIMIT)
                .list();
        if (candidates.isEmpty()) {
            return List.of();
        }

        Map<String, ClientMatch> likely = likelyClients(candidates);
        List<String> candidateKeys = candidates.stream().map(SlackUnmatchedCompany::getNameKey).toList();
        Map<String, Integer> channelSpread = channelSpread(candidateKeys);
        Set<String> gradedHigh = gradedHigh(candidateKeys);

        List<SlackUnmatchedCompany> rows = new ArrayList<>();
        int heldBack = 0;
        for (SlackUnmatchedCompany candidate : candidates) {
            if (isActionable(candidate, likely.get(candidate.getNameKey()), channelSpread,
                    gradedHigh.contains(candidate.getNameKey()))) {
                rows.add(candidate);
            } else {
                heldBack++;
            }
        }
        if (heldBack > 0) {
            // Counts only. A company name is the one thing this module deliberately does not
            // write to a log it does not control.
            log.infof("Slack suggestions: %d name(s) held back — heard once, by one colleague,"
                    + " in one channel, and matching no client we have", heldBack);
        }
        if (rows.isEmpty()) {
            return List.of();
        }
        if (rows.size() > capped) {
            rows = rows.subList(0, capped);
        }

        List<String> nameKeys = rows.stream().map(SlackUnmatchedCompany::getNameKey).toList();
        Map<String, List<String>> peopleByName = peopleByName(nameKeys);
        Map<String, Trail> trails = trails(nameKeys);
        Map<String, String> channelNames = channelNames();

        List<SlackSuggestionDTO> suggestions = new ArrayList<>();
        for (SlackUnmatchedCompany row : rows) {
            List<String> uuids = peopleByName.getOrDefault(row.getNameKey(), List.of());
            List<PersonDTO> people = new ArrayList<>();
            for (String uuid : uuids) {
                if (people.size() >= MAX_PEOPLE_SHOWN) {
                    break;
                }
                PersonDTO person = PersonDTO.from(User.findById(uuid));
                if (person != null) {
                    people.add(person);
                }
            }

            Trail trail = trails.get(row.getNameKey());
            List<String> channels = new ArrayList<>();
            if (trail != null) {
                for (String channelId : trail.channelIds()) {
                    String name = channelNames.get(channelId);
                    if (name != null) {
                        // A channel somebody has since removed from the configuration keeps
                        // its mentions (D15) but loses its name: nothing about a channel is
                        // stored on a sighting, deliberately.
                        channels.add(name);
                    }
                }
            }

            suggestions.add(new SlackSuggestionDTO(
                    row.getNameKey(),
                    row.getDisplayName(),
                    row.getMentions90d(),
                    row.getMentionsTotal(),
                    row.getFirstSeen(),
                    row.getLastSeen(),
                    channels,
                    people,
                    Math.max(row.getPeopleCount(), people.size()),
                    trail == null ? null : trail.permalink(),
                    likely.containsKey(row.getNameKey()) ? likely.get(row.getNameKey()).uuid() : null,
                    likely.containsKey(row.getNameKey()) ? likely.get(row.getNameKey()).name() : null,
                    isCorroborated(row, channelSpread)));
        }
        return suggestions;
    }

    // ------------------------------------------------------------------------
    // A company nobody has heard of, created as a prospect
    // ------------------------------------------------------------------------

    /**
     * Turns the hints the model was confident about into PROSPECT rows, so a new company
     * has an account page from the morning after it is first talked about instead of
     * waiting for somebody to notice a panel.
     *
     * <h2>Why this is gated three ways</h2>
     * This writes CRM rows from model output, which is the thing the hints panel exists to
     * avoid. The evidence for caution is this feature's own first two runs: of nine hints,
     * four were companies Intra already had under another spelling — {@code NN} for NOVO
     * NORDISK A/S, {@code KDS} for Klimadatastyrelsen, {@code Devoteam} (twice over), and
     * {@code Københavns Kommune} for "Københavns Kommune KFF". {@code createProspect} only
     * de-duplicates on an EXACT name, so unguarded this would have created a client called
     * "NN" beside NOVO NORDISK A/S — and a duplicate client row is what broke this very
     * module's alias resolution for Devoteam.
     *
     * <p>So a name is created only when all three hold:
     * <ul>
     *   <li><b>The flag is on.</b> It writes client rows without a person in the loop, and
     *       turning that off must not need a deploy.</li>
     *   <li><b>{@link #likelyClients} found nothing.</b> The prefix-and-initials matcher is
     *       what stands between this and a second Novo Nordisk.</li>
     *   <li><b>The model graded a sighting HIGH.</b> A passing name-drop does not become a
     *       row in the CRM; a LEAD, a PROPOSAL or a tender does.</li>
     * </ul>
     *
     * <p>The hint is then LINKED to what was created, so it leaves the panel and its name
     * becomes an alias — the same end state as somebody pressing Add, reached without them.
     * {@code createProspect} returns the existing client rather than duplicating when the
     * name matches one exactly, so a race with a human pressing Add resolves to one company.
     *
     * <h2>No CVR</h2>
     * The row is created with {@code cvr} null on purpose. A CVR is an identifier that ends
     * up on invoices and in e-conomic, and a model asked for one will produce eight
     * plausible digits for a company it has never seen. Nothing here guesses it: the
     * nightly CVR job finds these rows with {@code type = 'PROSPECT' and cvr is null} and
     * fills them from the register, which is the only source that can be right.
     *
     * @return how many companies were created
     */
    public int createProspectsFromConfidentHints(String actor) {
        if (!featureFlag.isAutoProspectEnabled()) {
            return 0;
        }
        List<SlackUnmatchedCompany> candidates = SlackUnmatchedCompany
                .find("status = ?1", UnmatchedCompanyStatus.NEW)
                .page(0, MAX_LIMIT)
                .list();
        if (candidates.isEmpty()) {
            return 0;
        }
        List<String> keys = candidates.stream().map(SlackUnmatchedCompany::getNameKey).toList();
        Map<String, ClientMatch> likely = likelyClients(candidates);
        Set<String> gradedHigh = gradedHigh(keys);

        int created = 0;
        for (SlackUnmatchedCompany row : candidates) {
            if (likely.containsKey(row.getNameKey()) || !gradedHigh.contains(row.getNameKey())) {
                continue;
            }
            try {
                Client prospect = prospects.createProspect(row.getDisplayName(), null, null, false,
                        "Heard in Slack — " + row.getDisplayName(), actor);
                row.setStatus(UnmatchedCompanyStatus.LINKED);
                row.setLinkedClientUuid(prospect.getUuid());
                row.setDecidedBy(actor);
                row.setDecidedAt(LocalDateTime.now());
                row.setUpdatedAt(LocalDateTime.now());
                row.persist();
                created++;
                // The uuid, never the name: a company name a model read out of somebody's
                // sentence does not go to a log this module does not control.
                log.infof("Slack hint created a prospect: client=%s actor=%s", prospect.getUuid(), actor);
            } catch (WebApplicationException e) {
                // A name of one character, or an owner that vanished. One bad hint does not
                // stop the rest, and the hint stays NEW so a person still sees it.
                log.warnf("Slack hint could not be created as a prospect (%s)", e.getResponse().getStatus());
            }
        }
        return created;
    }

    // ------------------------------------------------------------------------
    // Is this name a company we already have?
    // ------------------------------------------------------------------------

    /** A client a hint probably already is. */
    record ClientMatch(String uuid, String name) {
    }

    /**
     * Words that say what KIND of company something is, not which one. Dropped before
     * initials are taken, so "NOVO NORDISK A/S" yields NN and not NNAS.
     *
     * <p>The Danish forms written with a slash — A/S, I/S, K/S, P/S — do not appear here,
     * because {@code nameKey} has already turned the slash into a space by the time this
     * runs: "a s" is two words of one letter each, and {@link #initialsOf} drops any word
     * that short. That rule covers all four at once and needs no list to be kept current.
     */
    private static final Set<String> LEGAL_FORMS = Set.of(
            "as", "aps", "is", "ks", "ps", "amba", "fmba", "smba",
            "gmbh", "ltd", "plc", "inc", "llc", "oy", "nv", "bv", "sa", "ag");

    /** An abbreviation worth testing is 2-5 letters and no digits — "NN", "DOMST", "UFST". */
    private static final Pattern ABBREVIATION = Pattern.compile("^\\p{IsAlphabetic}{2,5}$");

    /**
     * For each hint, the one client it probably already is — or nothing.
     *
     * <p>Two rules, both token-adjacent, because a looser one files another company's news
     * on a client's page:
     * <ul>
     *   <li><b>Prefix at a word boundary.</b> "Københavns Kommune" is "Københavns Kommune
     *       KFF" with a qualifier; "Novo" is NOT "Novo Nordisk", because a hint that is one
     *       word of a multi-word name is exactly the ambiguous case — it would fit Novo
     *       Holdings, Novo Nordisk, Novonesis and the Foundation alike. So the hint must
     *       carry at least two words to match by prefix.</li>
     *   <li><b>Initials of every significant word.</b> "NN" is NOVO NORDISK A/S. Every
     *       word counts, never a prefix of the words: taking the first two of "Novo Nordisk
     *       Foundation" would also yield NN and make the match ambiguous with the one that
     *       is actually right.</li>
     * </ul>
     *
     * <p>A hint that fits two clients gets neither — the same rule
     * {@code SlackSourceSyncService.resolveAliases} applies to an ambiguous alias, and for
     * the same reason: when two are possible, neither is meant. That is what keeps the
     * duplicate "Devoteam" rows from producing a confident wrong answer.
     */
    private Map<String, ClientMatch> likelyClients(List<SlackUnmatchedCompany> candidates) {
        Set<String> keys = new HashSet<>();
        for (SlackUnmatchedCompany candidate : candidates) {
            keys.add(candidate.getNameKey());
        }
        Map<String, ClientMatch> found = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (Client client : Client.<Client>listAll()) {
            String name = client.getName();
            if (name == null || name.isBlank()) {
                continue;
            }
            String clientKey = SlackMentionExtractionService.nameKey(name);
            if (clientKey.isEmpty()) {
                continue;
            }
            String initials = initialsOf(clientKey);
            for (String key : keys) {
                if (ambiguous.contains(key) || !matches(key, clientKey, initials)) {
                    continue;
                }
                ClientMatch existing = found.get(key);
                if (existing != null && !existing.uuid().equals(client.getUuid())) {
                    // Two clients answer to it — including the case where one client is
                    // simply in the table twice. Offer neither.
                    found.remove(key);
                    ambiguous.add(key);
                    continue;
                }
                found.put(key, new ClientMatch(client.getUuid(), name));
            }
        }
        return found;
    }

    /** Does this hint key name that client, by prefix or by initials? Package-private: this is the
     * rule a wrong link would come from, so it is tested directly rather than through Panache. */
    static boolean matches(String hintKey, String clientKey, String clientInitials) {
        if (hintKey.isEmpty() || clientKey.isEmpty()) {
            return false;
        }
        if (hintKey.equals(clientKey)) {
            return true;
        }
        // Prefix, at a word boundary, and only for a hint that is itself several words.
        if (hintKey.contains(" ") && clientKey.startsWith(hintKey + " ")) {
            return true;
        }
        return !clientInitials.isEmpty()
                && ABBREVIATION.matcher(hintKey).matches()
                && hintKey.equals(clientInitials);
    }

    /**
     * The first letter of every word that carries identity — not a legal form, and not a
     * single letter. Empty when fewer than two remain, because a one-word company has a
     * one-letter initial and that would match most of the workspace.
     */
    static String initialsOf(String clientKey) {
        StringBuilder initials = new StringBuilder();
        int words = 0;
        for (String word : clientKey.split(" ")) {
            // length < 2 catches the halves of A/S, I/S, K/S and P/S after normalisation.
            if (word.length() < 2 || LEGAL_FORMS.contains(word)) {
                continue;
            }
            words++;
            initials.append(word.charAt(0));
        }
        // A one-word name has a one-letter initial, which would match far too much.
        return words < 2 ? "" : initials.toString();
    }

    // ------------------------------------------------------------------------
    // Is this name worth showing yet?
    // ------------------------------------------------------------------------

    /** {@code name → distinct source channels it has been heard in}. */
    private Map<String, Integer> channelSpread(List<String> nameKeys) {
        if (nameKeys.isEmpty()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                select name_key, count(distinct channel_id) as channels
                  from slack_unmatched_company_sighting
                 where name_key in (:names)
                 group by name_key
                """)
                .setParameter("names", nameKeys)
                .getResultList();
        Map<String, Integer> spread = new LinkedHashMap<>();
        for (Object[] row : rows) {
            spread.put(String.valueOf(row[0]), intOf(row[1]));
        }
        return spread;
    }

    /**
     * The names any sighting of which the model graded HIGH.
     *
     * <p>Asked of the sightings rather than of the aggregate on purpose: the aggregate is
     * counts, and a name heard twice — once as a passing mention and once as a real opening
     * — must still be offered on the strength of the opening. One HIGH sighting is enough,
     * for ever, which is also why nothing here decays: a lead nobody acted on does not stop
     * having been a lead.
     */
    private Set<String> gradedHigh(List<String> nameKeys) {
        if (nameKeys.isEmpty()) {
            return Set.of();
        }
        @SuppressWarnings("unchecked")
        List<String> rows = em.createNativeQuery("""
                select distinct name_key
                  from slack_unmatched_company_sighting
                 where name_key in (:names)
                   and signal_type in (:signals)
                """)
                .setParameter("names", nameKeys)
                .setParameter("signals", SlackDigestContent.highSignals())
                .getResultList();
        return new HashSet<>(rows);
    }

    /**
     * More than one day, more than one colleague, or more than one channel. Any one of the
     * three is enough: three people saying it once each is a pattern, and so is one person
     * saying it in three channels, and so is one person saying it three weeks running.
     */
    static boolean isCorroborated(SlackUnmatchedCompany row, Map<String, Integer> channelSpread) {
        return row.getMentionsTotal() > 1
                || row.getPeopleCount() > 1
                || channelSpread.getOrDefault(row.getNameKey(), 1) > 1;
    }

    /**
     * Worth a person's attention: we probably have them already, the model graded one of
     * their sightings HIGH, or it is a pattern.
     *
     * <p>The middle test is the one that matters most and was missing longest. A company
     * nobody has heard of, named once, by one person, in one channel, is exactly the shape
     * of a first inbound — and that is also the shape of a name-drop. The counts cannot
     * tell them apart and never could; the model already had, and the verdict was being
     * discarded before it reached this table (V612). With it, "vi skal med i denne dialog"
     * about a company we do not have is offered the morning after it is said, instead of
     * waiting for somebody to say the name a second time.
     */
    static boolean isActionable(SlackUnmatchedCompany row, ClientMatch likely,
                                Map<String, Integer> channelSpread, boolean gradedHigh) {
        return likely != null || gradedHigh || isCorroborated(row, channelSpread);
    }

    /** {@code name → colleagues who wrote about it}, most recently heard first. */
    private Map<String, List<String>> peopleByName(List<String> nameKeys) {
        if (nameKeys.isEmpty()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                select s.name_key, a.user_uuid, max(s.sighted_on) as last_heard
                  from slack_unmatched_company_sighting s
                  join slack_unmatched_company_sighting_author a on a.sighting_uuid = s.uuid
                 where s.name_key in (:names)
                 group by s.name_key, a.user_uuid
                 order by s.name_key, last_heard desc
                """)
                .setParameter("names", nameKeys)
                .getResultList();
        Map<String, List<String>> byName = new LinkedHashMap<>();
        for (Object[] row : rows) {
            byName.computeIfAbsent(String.valueOf(row[0]), key -> new ArrayList<>())
                    .add(String.valueOf(row[1]));
        }
        return byName;
    }

    /**
     * What the ledger says about one name beyond its counts: the channels it was heard in,
     * freshest first, and the permalink of the most recent sighting that carried one.
     */
    private record Trail(Set<String> channelIds, String permalink) { }

    private Map<String, Trail> trails(List<String> nameKeys) {
        if (nameKeys.isEmpty()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                select name_key, channel_id, permalink
                  from slack_unmatched_company_sighting
                 where name_key in (:names)
                 order by name_key, sighted_on desc
                """)
                .setParameter("names", nameKeys)
                .getResultList();
        Map<String, Set<String>> channelsByName = new LinkedHashMap<>();
        Map<String, String> permalinkByName = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String nameKey = String.valueOf(row[0]);
            channelsByName.computeIfAbsent(nameKey, key -> new LinkedHashSet<>())
                    .add(String.valueOf(row[1]));
            if (row[2] != null) {
                permalinkByName.putIfAbsent(nameKey, String.valueOf(row[2]));
            }
        }
        Map<String, Trail> trails = new LinkedHashMap<>();
        channelsByName.forEach((nameKey, channelIds) ->
                trails.put(nameKey, new Trail(channelIds, permalinkByName.get(nameKey))));
        return trails;
    }

    /** {@code channel id → name}. Only the configuration knows what a channel is called. */
    private Map<String, String> channelNames() {
        Map<String, String> names = new LinkedHashMap<>();
        for (SlackSourceChannel channel : SlackSourceChannel.<SlackSourceChannel>listAll()) {
            names.put(channel.getChannelId(), channel.getChannelName());
        }
        return names;
    }

    /**
     * The names the matcher should treat as accounts on the next run: {@code name key →
     * client} for every hint somebody has linked (spec §4.3).
     *
     * <p>This is what makes a decision worth taking. Linking a hint does not merely close a
     * row on a panel — it teaches the lane a spelling it had no way of knowing, and from the
     * next run the mentions land on the account instead of coming back as a hint.
     */
    public Map<String, String> linkedAliases() {
        Map<String, String> aliases = new LinkedHashMap<>();
        List<SlackUnmatchedCompany> rows = SlackUnmatchedCompany.list(
                "status = ?1 and linkedClientUuid is not null", UnmatchedCompanyStatus.LINKED);
        for (SlackUnmatchedCompany row : rows) {
            aliases.put(row.getNameKey(), row.getLinkedClientUuid());
        }
        return aliases;
    }

    // ------------------------------------------------------------------------
    // Decide
    // ------------------------------------------------------------------------

    /**
     * Adds the company, says it is a client we already have, or never asks again.
     *
     * <p>All three write the decision and who took it, so the panel can never re-ask a
     * question somebody has answered and an audit can say who answered it.
     *
     * <p>ADD creates the prospect and then LINKS the hint to it, rather than deleting the
     * hint as "dealt with". The link is the point: the name the model read is not the name
     * the company was created under, and the row is the only place that correspondence
     * exists (D4).
     */
    @Transactional
    public SlackUnmatchedCompany decide(String rawNameKey, SlackSuggestionDecisionRequest request, String actor) {
        if (actor == null || actor.isBlank()) {
            throw new WebApplicationException(
                    "X-Requested-By is required — a decision records who took it", Response.Status.BAD_REQUEST);
        }
        SlackUnmatchedCompany row = SlackUnmatchedCompany.findById(nameKey(rawNameKey));
        if (row == null) {
            // Deliberately not echoing what was asked for: the key is a company name the
            // model wrote, and it does not belong in an error the caller already knows.
            throw new WebApplicationException("Unknown company hint", Response.Status.NOT_FOUND);
        }
        if (request == null || request.decision() == null || request.decision().isBlank()) {
            throw new WebApplicationException("A decision is required: ADD, LINK or IGNORE",
                    Response.Status.BAD_REQUEST);
        }

        switch (request.decision().trim().toUpperCase(Locale.ROOT)) {
            case "IGNORE" -> {
                row.setStatus(UnmatchedCompanyStatus.IGNORED);
                row.setLinkedClientUuid(null);
            }
            case "LINK" -> {
                Client client = requireClient(request.clientUuid());
                row.setStatus(UnmatchedCompanyStatus.LINKED);
                row.setLinkedClientUuid(client.getUuid());
            }
            case "ADD" -> {
                Client created = prospects.createProspect(request.name(), request.segment(),
                        request.ownerUuid(), request.startPursuing(),
                        "Heard in Slack — " + row.getDisplayName(), actor);
                row.setStatus(UnmatchedCompanyStatus.LINKED);
                row.setLinkedClientUuid(created.getUuid());
            }
            default -> throw new WebApplicationException(
                    "Unknown decision: " + request.decision() + " — use ADD, LINK or IGNORE",
                    Response.Status.BAD_REQUEST);
        }

        LocalDateTime now = LocalDateTime.now();
        row.setDecidedBy(actor);
        row.setDecidedAt(now);
        row.setUpdatedAt(now);
        row.persist();
        // The name is a model's reading of somebody's sentence and stays out of the log; the
        // client uuid is what an audit needs, and the row it was decided on is findable from
        // the actor and the minute.
        log.infof("Slack company hint decided: decision=%s client=%s actor=%s",
                row.getStatus(), row.getLinkedClientUuid(), actor);
        return row;
    }

    private Client requireClient(String clientUuid) {
        if (clientUuid == null || clientUuid.isBlank()) {
            throw new WebApplicationException("Pick the client the name belongs to",
                    Response.Status.BAD_REQUEST);
        }
        Client client = clientService.findByUuid(clientUuid.trim());
        if (client == null) {
            throw new WebApplicationException("Unknown client", Response.Status.NOT_FOUND);
        }
        return client;
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    /**
     * The identity of a hint, delegated rather than repeated: the extractor and this service
     * have to agree on it exactly, because a key computed two ways is a hint that can never
     * be decided — the panel would post a decision at a row that does not exist. There were
     * two implementations of this rule until one of them learned to fold punctuation and the
     * other did not, which is the whole argument for there being one.
     */
    static String nameKey(String raw) {
        return SlackMentionExtractionService.nameKey(raw);
    }

    /** Trimmed, blanks dropped, first occurrence wins — the order is "as read". */
    private static Set<String> distinct(Collection<String> values) {
        Set<String> distinct = new LinkedHashSet<>();
        if (values == null) {
            return distinct;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                distinct.add(value.trim());
            }
        }
        return distinct;
    }

    private static int intOf(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    /**
     * One sighting is one (name, channel, day). Deterministic so the lookback window the
     * lane re-reads every night overwrites its own rows instead of adding a mention a day,
     * exactly as {@code AccountSlackSyncService.digestUuid} does for (client, day).
     */
    static String sightingUuid(String nameKey, String channelId, LocalDate sightedOn) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest((nameKey + "|" + channelId + "|" + sightedOn)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16)
                    + "-" + hex.substring(16, 20) + "-" + hex.substring(20, 32);
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 is guaranteed present on every JVM; this branch exists only to satisfy
            // the checked exception.
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }
}
