package dk.trustworks.intranet.aggregates.crm.person.services;

import dk.trustworks.intranet.aggregates.crm.person.model.AccountPerson;
import dk.trustworks.intranet.aggregates.crm.person.model.AccountRelationClaim;
import dk.trustworks.intranet.dao.crm.model.ClientActivityLog;
import dk.trustworks.intranet.dao.crm.services.ClientActivityLogService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * "I know her, we worked together at KMD." One colleague's claim about one person at one
 * account (spec §3.5).
 *
 * <h2>Why this is the only typed thing in the registry</h2>
 * {@code account_person} is derived — no field on it is ever typed by a human — and a claim
 * narrows that promise on purpose. It is a statement <b>about</b> a person the sources already
 * found, never a way to create one: there is no path from this service to a new
 * {@code account_person} row, and {@link #upsert} refuses a person the registry does not hold.
 * Everything else on the tab is inferred, and inference cannot answer the question the tab
 * exists for — who can actually pick up the phone.
 *
 * <h2>The ownership rules, and why each one is here</h2>
 * <ul>
 *   <li><b>The person must be on this client.</b> {@link AccountPerson#findOnClient} takes
 *       both halves, and this service never looks a person up by uuid alone. A claim endpoint
 *       that trusted the person uuid would let anybody who can reach one account write onto
 *       any person in the registry — the classic insecure direct object reference, and this
 *       codebase has shipped that shape before.</li>
 *   <li><b>The actor is the {@code X-Requested-By} user, resolved by the caller.</b> Never a
 *       body field. A claim says "I know this person"; a body field would let somebody file
 *       that in a colleague's name, and since only the claimant may remove their own claim,
 *       it would also let them plant one nobody but a DBA could take out.</li>
 *   <li><b>Only the claimant may delete.</b> A claim by somebody else is a 403, not a silent
 *       404: the caller asked to remove something that exists, and telling them it does not is
 *       worse than telling them it is not theirs.</li>
 * </ul>
 *
 * <h2>Bean Validation is not active in this codebase</h2>
 * {@code quarkus-hibernate-validator} is absent, so every {@code @NotNull} and {@code @Valid}
 * anywhere in this repository is inert decoration. Every check below is hand-rolled plain Java
 * throwing {@link WebApplicationException} with an explicit status. The
 * {@code chk_account_relation_claim_strength} CHECK in V605 is a backstop and not the gate —
 * a 400 is a better answer than a constraint violation surfacing as a 500, and a named CHECK
 * has gone missing from both of this project's databases before.
 */
@JBossLog
@ApplicationScoped
public class AccountRelationshipClaimService {

    /**
     * How a claim shows up in {@code client_activity_log}.
     *
     * <p><b>There is no {@code source} column on that table and no {@code RELATIONSHIP} entity
     * type.</b> {@code ClientActivityLog} offers five constants — {@code CLIENT},
     * {@code CONTRACT}, {@code CLIENTDATA}, {@code CONTRACT_CONSULTANT},
     * {@code CONTRACT_PROJECT} — and the account feed's own {@code RELATIONSHIP} row is a
     * <i>derivation</i>, not a stored value: {@code AccountActivityService.relationshipRows}
     * selects {@code entity_type = 'CLIENT' and field_name = 'type'} and renders "Became a
     * customer". So a claim is logged under the nearest existing entity type,
     * {@link ClientActivityLog#TYPE_CLIENT}, and under a field name of its own, which is what
     * keeps it out of that query. Writing {@code field_name = 'type'} here would put "Became a
     * customer — first contract signed" on the account's timeline every time somebody said
     * they knew a person.
     *
     * <p><b>That query is not the only reader, and the second one is the trap.</b>
     * {@code AccountService.addedByForAll} — the "Added by" column on every row of
     * {@code GET /accounts}, rendered as "by <first name>" in the Contacts view — selects
     * {@code min(modified_by)} over {@code entity_type = 'CLIENT' and action = 'CREATED'}
     * <i>with no field-name predicate at all</i>, and {@code modified_by} is not the actor
     * this service validated: {@code ClientActivityLogService.resolveCurrentUser()} reads
     * {@code RequestHeaderHolder} itself, so it is whoever filed the claim. Before this
     * feature, {@code CLIENT} + {@code CREATED} was written in exactly two places
     * ({@code ClientResource} and {@code CalendarSuggestionService}), both genuine "this
     * company was added" events. Every employee holds {@code signals:write}; {@code min()}
     * over a {@code CHAR(36)} is lexicographic, so roughly half of all first claims would
     * have silently rewritten the attribution — and on a client that predates the activity
     * log the claim would be the ONLY {@code CREATED} row, crediting the claimant with
     * adding a company they had nothing to do with. Hence {@link #upsert} logs
     * {@code MODIFIED} for a first claim too, and {@code addedByForAll} additionally
     * requires a null field name as a backstop.
     */
    static final String ACTIVITY_FIELD = "relationshipClaim";

    @Inject
    ClientActivityLogService activityLogService;

    /**
     * Records or updates this colleague's claim on this person.
     *
     * <p>A second call from the same colleague updates the row rather than adding one —
     * {@code UNIQUE (person_uuid, user_uuid)}, and "how well do you know her today" has one
     * answer, so there is deliberately no history.
     *
     * @param clientUuid the account from the URL; the boundary, and never taken from the body
     * @param personUuid the person from the URL
     * @param actorUuid  the {@code X-Requested-By} user, already validated as a human by the
     *                   resource; re-checked here because this service is also reachable from
     *                   a future caller that forgets
     * @param strength   1 met once · 2 know each other · 3 good working relationship · 4 trusted
     * @param how        free text, optional, at most 255 characters
     * @throws WebApplicationException 400 on a bad strength, an over-long {@code how} or a
     *                                 missing actor; 404 when the person is not on that client
     */
    @Transactional
    public AccountRelationClaim upsert(String clientUuid, String personUuid, String actorUuid,
                                       int strength, String how) {
        String actor = requireActor(actorUuid);
        AccountPerson person = requirePerson(clientUuid, personUuid);

        if (strength < AccountRelationClaim.MIN_STRENGTH || strength > AccountRelationClaim.MAX_STRENGTH) {
            throw new WebApplicationException(
                    "A relationship strength is 1 to 4: 1 met once, 2 know each other, "
                            + "3 good working relationship, 4 trusted",
                    Response.Status.BAD_REQUEST);
        }
        String note = how == null ? null : how.trim();
        if (note != null && note.isEmpty()) {
            note = null;
        }
        if (note != null && note.length() > AccountRelationClaim.MAX_HOW_CHARS) {
            // Rejected rather than truncated: the text is a sentence a person wrote about how
            // they know somebody, and half of it says something they did not mean.
            throw new WebApplicationException(
                    "How you know them is at most " + AccountRelationClaim.MAX_HOW_CHARS + " characters",
                    Response.Status.BAD_REQUEST);
        }

        LocalDateTime now = LocalDateTime.now();
        AccountRelationClaim claim = AccountRelationClaim.findByPersonAndUser(person.getUuid(), actor);
        boolean created = claim == null;
        String previous = created ? null : String.valueOf(claim.getStrength());
        if (created) {
            claim = new AccountRelationClaim();
            claim.setUuid(UUID.randomUUID().toString());
            claim.setClientUuid(person.getClientUuid());
            claim.setPersonUuid(person.getUuid());
            claim.setUserUuid(actor);
            claim.setClaimedAt(now);
        }
        claim.setStrength(strength);
        claim.setHow(note);
        claim.setUpdatedAt(now);
        claim.persist();

        // Uuids and a number. The person's name is not logged, and neither is `how` — it is
        // free text a colleague typed and it names other people ("worked together at KMD").
        //
        // MODIFIED even for a first claim, deliberately — see ACTIVITY_FIELD's javadoc. A
        // CLIENT + CREATED row is read by AccountService.addedByForAll as "who put this
        // company into Intra", so writing one here re-attributes the company to whichever
        // colleague happened to say they know somebody there. `previous` already carries the
        // new-versus-update distinction (null on a first claim), so nothing is lost by it.
        activityLogService.logChange(person.getClientUuid(), ClientActivityLog.TYPE_CLIENT,
                person.getUuid(), null,
                ClientActivityLog.ACTION_MODIFIED,
                ACTIVITY_FIELD, previous, String.valueOf(strength));

        log.infof("Account relationship claim %s: client=%s person=%s strength=%d actor=%s",
                created ? "recorded" : "updated", person.getClientUuid(), person.getUuid(), strength, actor);
        return claim;
    }

    /**
     * Removes this colleague's own claim.
     *
     * <p>Three answers, and the difference between the last two matters:
     * <ul>
     *   <li>the person is not on this client → <b>404</b>;</li>
     *   <li>somebody else has claimed this person but the caller has not → <b>403</b>, because
     *       a claim does exist and it is not theirs to remove;</li>
     *   <li>nobody has claimed this person at all → <b>404</b>, because there is nothing here.</li>
     * </ul>
     *
     * @throws WebApplicationException 400 on a missing actor, 403 for a non-claimant, 404 when
     *                                 the person or the claim is not there
     */
    @Transactional
    public void delete(String clientUuid, String personUuid, String actorUuid) {
        String actor = requireActor(actorUuid);
        AccountPerson person = requirePerson(clientUuid, personUuid);

        AccountRelationClaim claim = AccountRelationClaim.findByPersonAndUser(person.getUuid(), actor);
        if (claim == null) {
            List<AccountRelationClaim> others = AccountRelationClaim.listForPerson(person.getUuid());
            if (!others.isEmpty()) {
                throw new WebApplicationException(
                        "Only the colleague who said they know this person can remove it",
                        Response.Status.FORBIDDEN);
            }
            throw new WebApplicationException("There is no claim to remove", Response.Status.NOT_FOUND);
        }
        claim.delete();

        activityLogService.logChange(person.getClientUuid(), ClientActivityLog.TYPE_CLIENT,
                person.getUuid(), null, ClientActivityLog.ACTION_DELETED,
                ACTIVITY_FIELD, String.valueOf(claim.getStrength()), null);

        log.infof("Account relationship claim removed: client=%s person=%s actor=%s",
                person.getClientUuid(), person.getUuid(), actor);
    }

    /**
     * The person, or a 404.
     *
     * <p><b>This is the ownership check and it must never trust the client.</b> The lookup
     * takes the client from the URL and the person from the URL and requires both to match one
     * row; a person on another account answers 404 and never 403, because "that person is not
     * on this account" is all a caller is entitled to learn — a 403 would confirm the uuid
     * exists somewhere.
     */
    private static AccountPerson requirePerson(String clientUuid, String personUuid) {
        AccountPerson person = AccountPerson.findOnClient(clientUuid, personUuid);
        if (person == null) {
            throw new WebApplicationException("Unknown person on this account", Response.Status.NOT_FOUND);
        }
        return person;
    }

    /**
     * The acting user, or a 400.
     *
     * <p>{@code RequestHeaderHolder.getUserUuid()} is never null — {@code HeaderInterceptor}
     * falls back through the JWT's {@code preferred_username} to the literal
     * {@code "anonymous"} — so a missing header arrives as the BFF's own client id rather than
     * as a blank. The resource re-validates the UUID shape before calling in; this is the
     * floor under that, so a caller that forgets writes nothing rather than writing a claim
     * attributed to a machine.
     */
    private static String requireActor(String actorUuid) {
        if (actorUuid == null || actorUuid.isBlank()) {
            throw new WebApplicationException(
                    "X-Requested-By is required — a claim records who says they know somebody",
                    Response.Status.BAD_REQUEST);
        }
        return actorUuid.trim();
    }
}
