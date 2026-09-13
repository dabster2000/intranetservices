package dk.trustworks.intranet.aggregates.crm.bid.services;

import dk.trustworks.intranet.aggregates.crm.bid.dto.BidDTO;
import dk.trustworks.intranet.aggregates.crm.bid.dto.BidRequest;
import dk.trustworks.intranet.aggregates.crm.bid.model.Bid;
import dk.trustworks.intranet.aggregates.crm.bid.model.enums.BidGoNoGo;
import dk.trustworks.intranet.aggregates.crm.bid.model.enums.BidOutcome;
import dk.trustworks.intranet.aggregates.crm.bid.model.enums.BidType;
import dk.trustworks.intranet.dao.crm.services.ClientService;
import dk.trustworks.intranet.domain.user.entity.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Bid records (CRM spec §3.6).
 *
 * <h2>The two rules that keep the numbers honest</h2>
 * <ul>
 *   <li><b>A no-go has no outcome.</b> {@code goNoGo = NOGO} forces
 *       {@code outcome = NOGO}. A tender we declined is neither a win nor a loss and must
 *       never land in the win-rate denominator.</li>
 *   <li><b>Only a loss names a competitor.</b> {@code competitor} is cleared unless the
 *       outcome is {@code LOST}. "We beat Netcompany" on a bid we won is a claim nobody
 *       verified — procurement rarely tells you who else bid — and it would then feed the
 *       lost-analysis roll-up as fact.</li>
 * </ul>
 *
 * <p>Win rate is NOT stored. It is won ÷ (won + lost) over decided bids, computed where it
 * is shown. Storing it would mean recomputing it on every write and getting it wrong once.
 *
 * <p>Validation is hand-rolled: bean validation is not active in this build.
 */
@JBossLog
@ApplicationScoped
public class BidService {

    public static final int MAX_TITLE_CHARS = 300;
    public static final int MAX_COMPETITOR_CHARS = 200;
    public static final int MAX_POST_MORTEM_CHARS = 8000;

    @Inject
    ClientService clientService;

    public List<BidDTO> forClient(String clientUuid) {
        if (clientUuid == null || clientUuid.isBlank()) {
            return List.of();
        }
        return Bid.<Bid>list("clientUuid = ?1 order by dueDate desc", clientUuid.trim())
                .stream().map(BidDTO::from).toList();
    }

    @Transactional
    public BidDTO create(BidRequest request, String actor) {
        requireActor(actor);
        if (request == null) {
            throw new WebApplicationException("A body is required", Response.Status.BAD_REQUEST);
        }
        if (clientService.findByUuid(nonBlank(request.clientUuid(), "clientUuid")) == null) {
            throw new WebApplicationException("Unknown client", Response.Status.BAD_REQUEST);
        }

        LocalDateTime now = LocalDateTime.now();
        Bid bid = new Bid();
        bid.setUuid(UUID.randomUUID().toString());
        bid.setClientUuid(request.clientUuid().trim());
        bid.setGoNoGo(BidGoNoGo.PENDING);
        bid.setOutcome(BidOutcome.OPEN);
        bid.setCreatedAt(now);
        bid.setCreatedBy(actor);
        apply(bid, request, actor, now, true);
        bid.persist();

        log.infof("Bid created: uuid=%s client=%s actor=%s", bid.getUuid(), bid.getClientUuid(), actor);
        return BidDTO.from(bid);
    }

    @Transactional
    public BidDTO update(String bidUuid, BidRequest request, String actor) {
        requireActor(actor);
        Bid bid = require(bidUuid);
        apply(bid, request, actor, LocalDateTime.now(), false);
        bid.persist();
        return BidDTO.from(bid);
    }

    @Transactional
    public void delete(String bidUuid, String actor) {
        requireActor(actor);
        Bid bid = require(bidUuid);
        log.infof("Bid deleted: uuid=%s client=%s actor=%s", bid.getUuid(), bid.getClientUuid(), actor);
        bid.delete();
    }

    private void apply(Bid bid, BidRequest request, String actor, LocalDateTime now, boolean creating) {
        if (request != null) {
            if (creating || request.title() != null) {
                String title = trimTo(request.title(), MAX_TITLE_CHARS);
                if (title == null) {
                    throw new WebApplicationException("A bid needs a title", Response.Status.BAD_REQUEST);
                }
                bid.setTitle(title);
            }
            if (creating || request.type() != null) {
                bid.setType(parse(BidType.class, orDefault(request.type(), "TILBUD"), "type"));
            }
            if (request.goNoGo() != null) {
                bid.setGoNoGo(parse(BidGoNoGo.class, request.goNoGo(), "goNoGo"));
            }
            if (request.clearPrice()) {
                bid.setPrice(null);
            } else if (request.price() != null) {
                if (request.price() < 0) {
                    throw new WebApplicationException("A bid price cannot be negative",
                            Response.Status.BAD_REQUEST);
                }
                bid.setPrice(request.price());
            }
            if (creating || request.dueDate() != null) {
                bid.setDueDate(request.dueDate() == null ? LocalDate.now() : request.dueDate());
            }
            if (request.outcome() != null) {
                bid.setOutcome(parse(BidOutcome.class, request.outcome(), "outcome"));
            }
            if (request.clearCompetitor()) {
                bid.setCompetitor(null);
            } else if (request.competitor() != null) {
                bid.setCompetitor(trimTo(request.competitor(), MAX_COMPETITOR_CHARS));
            }
            if (request.postMortem() != null) {
                bid.setPostMortem(trimTo(request.postMortem(), MAX_POST_MORTEM_CHARS));
            }
            if (request.clearLead()) {
                bid.setLeadUuid(null);
            } else if (request.leadUuid() != null && !request.leadUuid().isBlank()) {
                bid.setLeadUuid(request.leadUuid().trim());
            }
            if (creating || request.ownerUuid() != null) {
                bid.setOwnerUuid(requireUser(orDefault(request.ownerUuid(), actor)));
            }
        }

        // A bid we declined has no result, and only a loss names a competitor. Applied
        // after the field-by-field assignment so it holds whatever order the caller sent.
        if (bid.getGoNoGo() == BidGoNoGo.NOGO) {
            bid.setOutcome(BidOutcome.NOGO);
            bid.setPrice(null);
        } else if (bid.getOutcome() == BidOutcome.NOGO) {
            // The outcome says no-go but the decision does not; the decision is the fact.
            bid.setOutcome(BidOutcome.OPEN);
        }
        if (bid.getOutcome() != BidOutcome.LOST) {
            bid.setCompetitor(null);
        }

        bid.setModifiedAt(now);
        bid.setModifiedBy(actor);
    }

    private Bid require(String bidUuid) {
        if (bidUuid == null || bidUuid.isBlank()) {
            throw new WebApplicationException("A bid uuid is required", Response.Status.BAD_REQUEST);
        }
        Bid bid = Bid.findById(bidUuid.trim());
        if (bid == null) {
            throw new WebApplicationException("Unknown bid", Response.Status.NOT_FOUND);
        }
        return bid;
    }

    private String requireUser(String userUuid) {
        if (userUuid == null || userUuid.isBlank()) {
            throw new WebApplicationException("A bid needs an owner", Response.Status.BAD_REQUEST);
        }
        if (User.<User>findById(userUuid.trim()) == null) {
            throw new WebApplicationException("Unknown colleague: " + userUuid, Response.Status.BAD_REQUEST);
        }
        return userUuid.trim();
    }

    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new WebApplicationException(
                    "X-Requested-By is required — a bid record says who filed it",
                    Response.Status.BAD_REQUEST);
        }
    }

    private static String nonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new WebApplicationException(field + " is required", Response.Status.BAD_REQUEST);
        }
        return value.trim();
    }

    static <E extends Enum<E>> E parse(Class<E> type, String raw, String field) {
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException("Unknown " + field + ": " + raw, Response.Status.BAD_REQUEST);
        }
    }

    static String trimTo(String raw, int maxChars) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > maxChars ? trimmed.substring(0, maxChars) : trimmed;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
