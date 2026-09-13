package dk.trustworks.intranet.aggregates.crm.gtm.services;

import dk.trustworks.intranet.aggregates.crm.account.dto.PersonDTO;
import dk.trustworks.intranet.aggregates.crm.account.model.ClientAccount;
import dk.trustworks.intranet.aggregates.crm.account.model.enums.AccountBand;
import dk.trustworks.intranet.aggregates.crm.account.services.AccountService;
import dk.trustworks.intranet.aggregates.crm.account.services.PersonRoleService;
import dk.trustworks.intranet.aggregates.crm.gtm.dto.GtmTeamDTO;
import dk.trustworks.intranet.aggregates.crm.gtm.dto.GtmTeamSectorsRequest;
import dk.trustworks.intranet.aggregates.crm.gtm.model.GtmTeamSector;
import dk.trustworks.intranet.aggregates.crm.sector.dto.SectorSummaryDTO;
import dk.trustworks.intranet.dao.bubbleservice.model.Bubble;
import dk.trustworks.intranet.dao.bubbleservice.model.BubbleMember;
import dk.trustworks.intranet.dao.bubbleservice.model.enums.BubbleType;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.model.enums.ClientSegment;
import dk.trustworks.intranet.dao.crm.model.enums.ClientType;
import dk.trustworks.intranet.dao.crm.services.ClientService;
import dk.trustworks.intranet.domain.user.entity.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * GTM teams (sectors spec §2, §6.1): the active {@code FOCUS} bubbles, which sectors each
 * covers, and which accounts point at it.
 *
 * <p>A GTM team is a bubble because it already is one — Offentlig Digitalisering, Grøn
 * Omstilling, Fremtidens Finansielle Sektor and Pharma &amp; Life Science exist with an
 * owner, members and a Slack channel. This service adds the two things a bubble does not
 * know: the segments it covers ({@code gtm_team_sector}) and the accounts that chose it
 * ({@code client_account.gtm_bubble_uuid}). Membership itself stays where it is, on the
 * bubble.
 */
@JBossLog
@ApplicationScoped
public class GtmTeamService {

    /** How many un-teamed accounts a card suggests before it stops. */
    static final int MAX_SUGGESTED = 25;

    @Inject
    AccountService accountService;

    @Inject
    ClientService clientService;

    @Inject
    PersonRoleService personRoles;

    public List<GtmTeamDTO> list() {
        Context context = load();
        List<GtmTeamDTO> teams = new ArrayList<>();
        for (Bubble bubble : activeFocusBubbles()) {
            teams.add(toDto(bubble, context));
        }
        return teams;
    }

    public GtmTeamDTO read(String bubbleUuid) {
        return toDto(requireFocusBubble(bubbleUuid), load());
    }

    /** {@code bubble uuid → segments}, for every team at once. */
    public Map<String, Set<ClientSegment>> sectorsByBubble() {
        Map<String, Set<ClientSegment>> map = new HashMap<>();
        for (GtmTeamSector row : GtmTeamSector.<GtmTeamSector>listAll()) {
            map.computeIfAbsent(row.getBubbleUuid(), key -> EnumSet.noneOf(ClientSegment.class)).add(row.getSegment());
        }
        return map;
    }

    /** {@code segment → the active GTM teams covering it}, in name order. */
    public Map<ClientSegment, List<SectorSummaryDTO.TeamRefDTO>> teamsBySegment() {
        Map<String, Set<ClientSegment>> sectors = sectorsByBubble();
        Map<ClientSegment, List<SectorSummaryDTO.TeamRefDTO>> map = new EnumMap<>(ClientSegment.class);
        for (Bubble bubble : activeFocusBubbles()) {
            for (ClientSegment segment : sectors.getOrDefault(bubble.getUuid(), Set.of())) {
                map.computeIfAbsent(segment, key -> new ArrayList<>())
                        .add(new SectorSummaryDTO.TeamRefDTO(bubble.getUuid(), bubble.getName()));
            }
        }
        return map;
    }

    /** True when the bubble exists, is active and is a FOCUS bubble — what a GTM team is. */
    public boolean isActiveFocusBubble(String bubbleUuid) {
        Bubble bubble = bubbleUuid == null ? null : Bubble.findById(bubbleUuid.trim());
        return bubble != null && bubble.isActive() && bubble.getType() == BubbleType.FOCUS;
    }

    /**
     * Replaces the set of sectors a team covers. The team's owner or co-owner may do it,
     * and so may management — resolved from the person, never from the token.
     */
    @Transactional
    public GtmTeamDTO replaceSectors(String bubbleUuid, GtmTeamSectorsRequest request, String actor) {
        if (actor == null || actor.isBlank()) {
            throw new WebApplicationException(
                    "X-Requested-By is required — a change to a team's sectors records who made it",
                    Response.Status.BAD_REQUEST);
        }
        Bubble bubble = requireFocusBubble(bubbleUuid);
        if (!mayEditTeam(bubble, actor)) {
            throw new WebApplicationException(
                    "Only the team's lead, or a partner or admin, changes which sectors it covers",
                    Response.Status.FORBIDDEN);
        }

        LinkedHashSet<ClientSegment> wanted = new LinkedHashSet<>();
        if (request != null && request.segments() != null) {
            for (String raw : request.segments()) {
                if (raw != null && !raw.isBlank()) {
                    wanted.add(parseSegment(raw));
                }
            }
        }

        GtmTeamSector.delete("bubbleUuid", bubble.getUuid());
        LocalDateTime now = LocalDateTime.now();
        for (ClientSegment segment : wanted) {
            GtmTeamSector row = new GtmTeamSector();
            row.setUuid(UUID.randomUUID().toString());
            row.setBubbleUuid(bubble.getUuid());
            row.setSegment(segment);
            row.setCreatedAt(now);
            row.setCreatedBy(actor);
            row.persist();
        }
        log.infof("GTM team sectors replaced: bubble=%s sectors=%s actor=%s", bubble.getUuid(), wanted, actor);
        return read(bubble.getUuid());
    }

    /** The team's lead or co-lead, or management. Pure, so the rule is testable without a container. */
    static boolean mayEditTeam(String owner, String coOwner, String actor, boolean management) {
        if (management) {
            return true;
        }
        if (actor == null || actor.isBlank()) {
            return false;
        }
        return actor.equals(owner) || actor.equals(coOwner);
    }

    private boolean mayEditTeam(Bubble bubble, String actor) {
        return mayEditTeam(bubble.getOwner(), bubble.getCoowner(), actor, personRoles.isManagement(actor));
    }

    // ------------------------------------------------------------------------
    // Assembly
    // ------------------------------------------------------------------------

    private record Context(
            List<Client> clients,
            Map<String, AccountBand> bands,
            Map<String, List<PersonDTO>> supported,
            Map<String, ClientAccount> accounts,
            Map<String, Set<ClientSegment>> sectors) {
    }

    private Context load() {
        Map<String, ClientAccount> accounts = new HashMap<>();
        for (ClientAccount account : ClientAccount.<ClientAccount>listAll()) {
            accounts.put(account.getClientUuid(), account);
        }
        return new Context(
                clientService.listByType(ClientType.CLIENT),
                accountService.bandsForAll(),
                accountService.supportedByForAll(),
                accounts,
                sectorsByBubble());
    }

    private GtmTeamDTO toDto(Bubble bubble, Context context) {
        Set<ClientSegment> sectors = context.sectors().getOrDefault(bubble.getUuid(), Set.of());

        List<GtmTeamDTO.GtmAccountDTO> accounts = new ArrayList<>();
        List<GtmTeamDTO.GtmAccountDTO> suggested = new ArrayList<>();
        for (Client client : context.clients()) {
            ClientAccount account = context.accounts().get(client.getUuid());
            AccountBand band = context.bands().getOrDefault(client.getUuid(), AccountBand.BACKLOG);
            String teamUuid = account == null ? null : account.getGtmBubbleUuid();
            if (bubble.getUuid().equals(teamUuid)) {
                accounts.add(accountRow(client, band, context));
            } else if (teamUuid == null
                    && band != AccountBand.BACKLOG
                    && sectors.contains(segmentOf(client))
                    && suggested.size() < MAX_SUGGESTED) {
                suggested.add(accountRow(client, band, context));
            }
        }
        Comparator<GtmTeamDTO.GtmAccountDTO> order = Comparator
                .comparing((GtmTeamDTO.GtmAccountDTO row) -> AccountBand.valueOf(row.band()).ordinal())
                .thenComparing(GtmTeamDTO.GtmAccountDTO::clientName, String.CASE_INSENSITIVE_ORDER);
        accounts.sort(order);
        suggested.sort(order);

        List<PersonDTO> members = new ArrayList<>();
        if (bubble.getBubbleMembers() != null) {
            for (BubbleMember member : bubble.getBubbleMembers()) {
                PersonDTO person = person(member.getUseruuid());
                if (person != null) {
                    members.add(person);
                }
            }
            members.sort(Comparator.comparing(PersonDTO::name, String.CASE_INSENSITIVE_ORDER));
        }

        return new GtmTeamDTO(
                bubble.getUuid(),
                bubble.getName() == null ? "" : bubble.getName().trim(),
                bubble.getDescription(),
                bubble.getSlackchannel(),
                person(bubble.getOwner()),
                person(bubble.getCoowner()),
                members,
                sectors.stream().sorted().map(Enum::name).toList(),
                accounts,
                suggested);
    }

    private GtmTeamDTO.GtmAccountDTO accountRow(Client client, AccountBand band, Context context) {
        boolean backlog = band == AccountBand.BACKLOG;
        return new GtmTeamDTO.GtmAccountDTO(
                client.getUuid(),
                client.getName(),
                band.name(),
                segmentOf(client).name(),
                // Backlog accounts are listed without roles (spec §4.4): nothing is expected
                // of whoever happens to be on the client row.
                backlog ? null : person(client.getAccountmanager()),
                backlog ? List.of() : context.supported().getOrDefault(client.getUuid(), List.of()));
    }

    private List<Bubble> activeFocusBubbles() {
        return Bubble.list("type = ?1 and active = true order by name", BubbleType.FOCUS);
    }

    private Bubble requireFocusBubble(String bubbleUuid) {
        if (bubbleUuid == null || bubbleUuid.isBlank()) {
            throw new WebApplicationException("A team uuid is required", Response.Status.BAD_REQUEST);
        }
        Bubble bubble = Bubble.findById(bubbleUuid.trim());
        if (bubble == null || bubble.getType() != BubbleType.FOCUS) {
            throw new WebApplicationException("Unknown GTM team", Response.Status.NOT_FOUND);
        }
        return bubble;
    }

    static ClientSegment segmentOf(Client client) {
        return client.getSegment() == null ? ClientSegment.OTHER : client.getSegment();
    }

    static ClientSegment parseSegment(String raw) {
        try {
            return ClientSegment.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException("Unknown sector: " + raw, Response.Status.BAD_REQUEST);
        }
    }

    private static PersonDTO person(String userUuid) {
        if (userUuid == null || userUuid.isBlank()) {
            return null;
        }
        return PersonDTO.from(User.findById(userUuid.trim()));
    }
}
