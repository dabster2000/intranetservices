package dk.trustworks.intranet.aggregates.crm.account.services;

import dk.trustworks.intranet.aggregates.crm.account.dto.AccountRelationshipsDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.PersonDTO;
import dk.trustworks.intranet.aggregates.crm.calendar.services.CalendarConsentService;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.services.ClientService;
import dk.trustworks.intranet.domain.user.entity.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * The relationship graph for one account (CRM spec §3.7): who at Trustworks knows whom at
 * the client, and how.
 *
 * <p><b>Nothing here is maintained by hand.</b> There is no contact table and there never
 * will be — the spec is explicit that a contact database nobody would maintain is what
 * this replaces. Every node and every edge is read from records that already exist:
 *
 * <ul>
 *   <li><b>Trustworks side</b> — the account manager plus everyone on a contract for this
 *       client. These are people who demonstrably work the account.</li>
 *   <li><b>MET edges</b> — {@code account_meeting}: calendar metadata from mailboxes whose
 *       owners consented, attributed to this client by the attendee's e-mail domain. The
 *       weight is the number of meetings the two were both in; {@code lastMet} is the most
 *       recent.</li>
 *   <li><b>KNOWS edges</b> — {@code account_signal}: a colleague wrote down how they know
 *       somebody, and that sentence is the edge's label. Weight 0, no date — it is an
 *       acquaintance, not a meeting, and drawing it as one would overstate it.</li>
 * </ul>
 *
 * <p><b>An empty graph is a real answer.</b> If nobody on the account has consented to
 * calendar reads and nobody has filed a signal, there is nothing to draw, and
 * {@code consentedPeople} / {@code totalPeople} let the tab say WHY it is empty instead of
 * leaving the reader to assume the client has no relationships.
 */
@JBossLog
@ApplicationScoped
public class AccountRelationshipService {

    /** How many external people the graph draws before it stops. Beyond this it is unreadable. */
    private static final int MAX_EXTERNAL_PEOPLE = 12;

    @Inject
    EntityManager em;

    @Inject
    ClientService clientService;

    @Inject
    CalendarConsentService consentService;

    public AccountRelationshipsDTO forClient(String clientUuid) {
        Client client = clientService.findByUuid(clientUuid);
        if (client == null) {
            return new AccountRelationshipsDTO(List.of(), List.of(), List.of(), 0, 0);
        }

        Map<String, PersonDTO> trustworksPeople = trustworksPeople(clientUuid, client.getAccountmanager());
        Map<String, AccountRelationshipsDTO.ExternalPersonDTO> externals = new LinkedHashMap<>();
        List<AccountRelationshipsDTO.RelationEdgeDTO> edges = new ArrayList<>();

        collectMeetingEdges(clientUuid, trustworksPeople, externals, edges);
        collectSignalEdges(clientUuid, trustworksPeople, externals, edges);

        // Keep only edges whose BOTH ends survived the external cap, so the graph never
        // draws a line to a node it did not render.
        List<AccountRelationshipsDTO.ExternalPersonDTO> externalList = externals.values().stream()
                .limit(MAX_EXTERNAL_PEOPLE)
                .toList();
        LinkedHashSet<String> keptNames = new LinkedHashSet<>();
        externalList.forEach(person -> keptNames.add(person.name()));
        List<AccountRelationshipsDTO.RelationEdgeDTO> keptEdges = edges.stream()
                .filter(edge -> keptNames.contains(edge.externalName()))
                .toList();

        int consented = 0;
        for (String userUuid : trustworksPeople.keySet()) {
            if (consentService.isEnabled(userUuid)) {
                consented++;
            }
        }

        return new AccountRelationshipsDTO(
                List.copyOf(trustworksPeople.values()),
                externalList,
                keptEdges,
                consented,
                trustworksPeople.size());
    }

    /**
     * The Trustworks side: the account manager first (they are the Responsible), then
     * everyone who has been on a contract for this client, most recent contract first.
     */
    private Map<String, PersonDTO> trustworksPeople(String clientUuid, String accountManagerUuid) {
        Map<String, PersonDTO> people = new LinkedHashMap<>();
        if (accountManagerUuid != null && !accountManagerUuid.isBlank()) {
            User owner = User.findById(accountManagerUuid.trim());
            if (owner != null) {
                people.put(owner.getUuid(), PersonDTO.from(owner));
            }
        }

        Query query = em.createNativeQuery("""
                select distinct cc.useruuid
                  from contract_consultants cc
                  join contracts c on c.uuid = cc.contractuuid
                 where c.clientuuid = :clientUuid
                """);
        query.setParameter("clientUuid", clientUuid);
        @SuppressWarnings("unchecked")
        List<Object> uuids = query.getResultList();
        for (Object raw : uuids) {
            if (raw == null) {
                continue;
            }
            String uuid = raw.toString();
            if (people.containsKey(uuid)) {
                continue;
            }
            User user = User.findById(uuid);
            if (user != null) {
                people.put(uuid, PersonDTO.from(user));
            }
        }
        return people;
    }

    /**
     * MET edges, aggregated in SQL: one row per (Trustworks person, external person) with a
     * count and the latest date. Doing the aggregation in the database rather than in Java
     * keeps a heavily-met account from loading thousands of attendee rows to count them.
     */
    private void collectMeetingEdges(String clientUuid,
                                     Map<String, PersonDTO> trustworksPeople,
                                     Map<String, AccountRelationshipsDTO.ExternalPersonDTO> externals,
                                     List<AccountRelationshipsDTO.RelationEdgeDTO> edges) {
        Query query = em.createNativeQuery("""
                select m.user_uuid,
                       coalesce(a.display_name, a.email) as external_name,
                       count(distinct m.uuid)            as meetings,
                       max(m.occurred_at)                as last_met
                  from account_meeting m
                  join account_meeting_attendee a on a.meeting_uuid = m.uuid
                 where m.client_uuid = :clientUuid
                 group by m.user_uuid, coalesce(a.display_name, a.email)
                 order by meetings desc, last_met desc
                """);
        query.setParameter("clientUuid", clientUuid);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        for (Object[] row : rows) {
            String userUuid = row[0] == null ? null : row[0].toString();
            String externalName = row[1] == null ? null : row[1].toString();
            if (userUuid == null || externalName == null || externalName.isBlank()) {
                continue;
            }
            PersonDTO twPerson = trustworksPeople.get(userUuid);
            if (twPerson == null) {
                // Somebody who met the client but is not on a contract and is not the owner.
                User user = User.findById(userUuid);
                if (user == null) {
                    continue;
                }
                twPerson = PersonDTO.from(user);
                trustworksPeople.put(userUuid, twPerson);
            }
            // A calendar carries no job title, so an external known only from meetings has
            // no role. Inventing one from the e-mail address would be a guess presented as
            // a fact.
            externals.putIfAbsent(externalName,
                    new AccountRelationshipsDTO.ExternalPersonDTO(
                            externalName, null, PersonDTO.initialsOf(externalName)));
            edges.add(new AccountRelationshipsDTO.RelationEdgeDTO(
                    twPerson.name(),
                    externalName,
                    ((Number) row[2]).intValue(),
                    AccountActivityService.toLocalDate(row[3]),
                    null));
        }
    }

    /** KNOWS edges — one per signal that named both a person and how the author knows them. */
    private void collectSignalEdges(String clientUuid,
                                    Map<String, PersonDTO> trustworksPeople,
                                    Map<String, AccountRelationshipsDTO.ExternalPersonDTO> externals,
                                    List<AccountRelationshipsDTO.RelationEdgeDTO> edges) {
        Query query = em.createNativeQuery("""
                select author_uuid, person_name, person_role, relation_text, created_at
                  from account_signal
                 where client_uuid = :clientUuid
                   and person_name is not null
                 order by created_at desc
                """);
        query.setParameter("clientUuid", clientUuid);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        for (Object[] row : rows) {
            String authorUuid = row[0] == null ? null : row[0].toString();
            String personName = row[1] == null ? null : row[1].toString();
            if (authorUuid == null || personName == null || personName.isBlank()) {
                continue;
            }
            String role = row[2] == null ? null : row[2].toString();
            String relation = row[3] == null ? null : row[3].toString();

            PersonDTO author = trustworksPeople.get(authorUuid);
            if (author == null) {
                User user = User.findById(authorUuid);
                if (user == null) {
                    continue;
                }
                author = PersonDTO.from(user);
                trustworksPeople.put(authorUuid, author);
            }

            AccountRelationshipsDTO.ExternalPersonDTO existing = externals.get(personName);
            if (existing == null) {
                externals.put(personName, new AccountRelationshipsDTO.ExternalPersonDTO(
                        personName, role, PersonDTO.initialsOf(personName)));
            } else if (existing.role() == null && role != null) {
                // A signal knows the role a calendar never does — fill it in.
                externals.put(personName, new AccountRelationshipsDTO.ExternalPersonDTO(
                        personName, role, existing.initials()));
            }

            if (relation != null && !relation.isBlank()) {
                edges.add(new AccountRelationshipsDTO.RelationEdgeDTO(
                        author.name(), personName, 0, (LocalDate) null, relation));
            }
        }
    }
}
