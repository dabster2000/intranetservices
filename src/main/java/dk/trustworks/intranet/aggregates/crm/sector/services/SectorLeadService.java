package dk.trustworks.intranet.aggregates.crm.sector.services;

import dk.trustworks.intranet.aggregates.crm.account.dto.PersonDTO;
import dk.trustworks.intranet.aggregates.crm.account.services.PersonRoleService;
import dk.trustworks.intranet.aggregates.crm.sector.dto.SectorLeadRequest;
import dk.trustworks.intranet.aggregates.crm.sector.model.SectorLead;
import dk.trustworks.intranet.dao.crm.model.enums.ClientSegment;
import dk.trustworks.intranet.domain.user.entity.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who leads a sector (sectors spec §4, §7).
 *
 * <p>Temporal, the {@code practice_lead} idiom: one current row per segment, and starting
 * a new lead ends the previous row rather than overwriting it. Exactly one current lead
 * per segment is enforced here — a second concurrent row would leave "who decides an
 * unowned account's signals" with two answers.
 *
 * <p><b>A partner-group decision.</b> Setting a lead needs ADMIN or PARTNER, resolved from
 * the PERSON in {@code X-Requested-By} through {@link PersonRoleService}, never from the
 * security context, which only ever sees the BFF's own credential.
 */
@JBossLog
@ApplicationScoped
public class SectorLeadService {

    @Inject
    PersonRoleService personRoles;

    /** The current lead per segment; segments with no lead are absent from the map. */
    public Map<ClientSegment, PersonDTO> currentLeads() {
        Map<ClientSegment, PersonDTO> leads = new EnumMap<>(ClientSegment.class);
        for (SectorLead row : currentRows()) {
            if (!leads.containsKey(row.getSegment())) {
                PersonDTO person = PersonDTO.from(User.findById(row.getUserUuid()));
                if (person != null) {
                    leads.put(row.getSegment(), person);
                }
            }
        }
        return leads;
    }

    public PersonDTO currentLead(ClientSegment segment) {
        return segment == null ? null : currentLeads().get(segment);
    }

    /** Whether this person is the current lead of the segment. */
    public boolean isCurrentLead(ClientSegment segment, String userUuid) {
        if (segment == null || userUuid == null || userUuid.isBlank()) {
            return false;
        }
        for (SectorLead row : currentRows()) {
            if (row.getSegment() == segment && userUuid.trim().equals(row.getUserUuid())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Names the lead from today, or clears it. Idempotent: naming the person who already
     * leads the sector changes nothing.
     */
    @Transactional
    public PersonDTO setLead(ClientSegment segment, SectorLeadRequest request, String actor) {
        requireActor(actor);
        if (segment == null) {
            throw new WebApplicationException("A sector is required", Response.Status.BAD_REQUEST);
        }
        if (!personRoles.isManagement(actor)) {
            throw new WebApplicationException(
                    "Only a partner or an admin names a sector lead", Response.Status.FORBIDDEN);
        }
        if (request == null) {
            throw new WebApplicationException("A body is required", Response.Status.BAD_REQUEST);
        }

        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();

        if (request.clear()) {
            endCurrent(segment, today, actor, now);
            log.infof("Sector lead cleared: segment=%s actor=%s", segment, actor);
            return null;
        }

        String userUuid = request.userUuid() == null ? "" : request.userUuid().trim();
        if (userUuid.isEmpty()) {
            throw new WebApplicationException("Pick a colleague, or clear the lead", Response.Status.BAD_REQUEST);
        }
        User user = User.findById(userUuid);
        if (user == null) {
            throw new WebApplicationException("Unknown colleague: " + userUuid, Response.Status.BAD_REQUEST);
        }
        if (isCurrentLead(segment, userUuid)) {
            return PersonDTO.from(user);
        }

        endCurrent(segment, today, actor, now);

        SectorLead row = new SectorLead();
        row.setUuid(UUID.randomUUID().toString());
        row.setSegment(segment);
        row.setUserUuid(userUuid);
        row.setStartdate(today);
        row.setEnddate(null);
        row.setCreatedAt(now);
        row.setCreatedBy(actor);
        row.setModifiedAt(now);
        row.setModifiedBy(actor);
        row.persist();

        log.infof("Sector lead set: segment=%s lead=%s actor=%s", segment, userUuid, actor);
        return PersonDTO.from(user);
    }

    private void endCurrent(ClientSegment segment, LocalDate today, String actor, LocalDateTime now) {
        for (SectorLead row : SectorLead.<SectorLead>list("segment = ?1 and enddate is null", segment)) {
            row.setEnddate(today);
            row.setModifiedAt(now);
            row.setModifiedBy(actor);
            row.persist();
        }
    }

    /** Newest start first, so the first row per segment is the one that counts. */
    private List<SectorLead> currentRows() {
        return SectorLead.list("enddate is null order by startdate desc, createdAt desc");
    }

    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new WebApplicationException(
                    "X-Requested-By is required — naming a sector lead records who did it",
                    Response.Status.BAD_REQUEST);
        }
    }
}
