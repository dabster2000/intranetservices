package dk.trustworks.intranet.aggregates.crm.plan.services;

import dk.trustworks.intranet.aggregates.crm.person.model.AccountPerson;
import dk.trustworks.intranet.aggregates.crm.person.model.enums.AccountPersonKind;
import dk.trustworks.intranet.aggregates.crm.plan.dto.PlanRequests;
import dk.trustworks.intranet.aggregates.crm.plan.model.ClientPlanRelation;
import dk.trustworks.intranet.aggregates.crm.plan.model.ClientPlanStakeholder;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.BuyingRole;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.Influence;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.RelationRole;
import dk.trustworks.intranet.domain.user.entity.User;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.withSettings;

/**
 * A star on the relationships tab is a seat on the plan (spec §3.6, V606).
 *
 * <p>What is actually at stake in these tests is that three separate surfaces keep agreeing
 * about one human. The registry knows who somebody is; the plan says they matter here; the
 * relationships tab draws both. So the interesting rules are the ones that stop the plan
 * having its own opinion — the name and title are <b>copied</b> from the registry and never
 * taken from the request body, and the person has to be one this client actually has.
 *
 * <p><b>Fast tier — no Quarkus boot, no database.</b> The two registry lookups
 * ({@code AccountPerson.findOnClient}, {@code User.findById}) are mocked statically, and
 * {@code ClientPlanRelation} rows are intercepted at construction, so what each write path
 * <i>decided</i> can be asserted without any of it reaching MariaDB. The transactional
 * methods that wrap these helpers are exercised against the local database during
 * verification.
 */
class AccountPlanStakeholderStarTest {

    private static final String CLIENT = "c1a1f3d2-0000-4444-8888-aaaaaaaaaaaa";
    private static final String PERSON = "p1a1f3d2-1111-4444-8888-cccccccccccc";
    private static final String STAKEHOLDER = "s1a1f3d2-2222-4444-8888-dddddddddddd";
    private static final String ACTOR = "e1a8f3d2-2f3b-4a9d-9d64-0d2d0d0b1aa1";
    private static final String TOMMY = "1e0a2c44-1111-4444-8888-aaaaaaaaaaaa";
    private static final String LUKAS = "2e0a2c44-2222-4444-8888-bbbbbbbbbbbb";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 14, 10, 30);

    private AccountPlanService service;

    /** Every relation row the service built, in order. Real setters, no session behind them. */
    private final List<ClientPlanRelation> relationsBuilt = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new AccountPlanService();
        relationsBuilt.clear();
    }

    // ------------------------------------------------------------------------
    // The star: the registry owns who somebody is
    // ------------------------------------------------------------------------

    /**
     * The body may say anything at all about the name and the title; the registry row wins.
     *
     * <p>This is not defensiveness for its own sake. The star popover has no name field —
     * nobody types a contact into the plan any more — so a body that could still set one
     * would only ever be a caller disagreeing with the registry, and the plan would then
     * show a different person from the one the relationships tab, the overview and the
     * claim on the same uuid are all talking about.
     */
    @Test
    void aStarCopiesTheNameAndTitleOffTheRegistryAndIgnoresWhatTheBodySays() {
        ClientPlanStakeholder row = newRow();
        try (MockedStatic<AccountPerson> registry = mockStatic(AccountPerson.class)) {
            registry.when(() -> AccountPerson.findOnClient(CLIENT, PERSON))
                    .thenReturn(person(AccountPersonKind.CONTACT, "Sara Louise Vest", "Programdirektør"));

            service.applyStakeholder(row, star("Somebody Else", "Something Else"), ACTOR, NOW, true);
        }

        assertEquals(PERSON, row.getPersonUuid(), "the seat keeps a pointer back to the registry row");
        assertEquals("Sara Louise Vest", row.getName(), "the name is the registry's, not the body's");
        assertEquals("Programdirektør", row.getTitle(), "so is the title");
    }

    /**
     * A registry row with no title at all still has to leave the column filled —
     * {@code title} is NOT NULL — so the em dash survives there. It is {@code unit} that
     * lost its placeholder, not {@code title}.
     */
    @Test
    void aStarOnSomebodyWithNoTitleFallsBackToTheEmDash() {
        ClientPlanStakeholder row = newRow();
        try (MockedStatic<AccountPerson> registry = mockStatic(AccountPerson.class)) {
            registry.when(() -> AccountPerson.findOnClient(CLIENT, PERSON))
                    .thenReturn(person(AccountPersonKind.CONTACT, "Sara Louise Vest", null));

            service.applyStakeholder(row, star(null, null), ACTOR, NOW, true);
        }

        assertEquals("—", row.getTitle());
        assertNull(row.getUnit(), "and a starred person still has no unit");
    }

    /**
     * The ownership check. A person uuid from another account is a 404 — never a 403, and
     * never a silent star: the client in the URL is the boundary, and a lookup by person
     * uuid alone would let anybody with {@code accounts:write} on one account pull a person
     * off any other onto their plan.
     */
    @Test
    void aPersonFromAnotherAccountCannotBeStarred() {
        try (MockedStatic<AccountPerson> registry = mockStatic(AccountPerson.class)) {
            registry.when(() -> AccountPerson.findOnClient(CLIENT, PERSON)).thenReturn(null);

            WebApplicationException error = assertThrows(WebApplicationException.class,
                    () -> service.applyStakeholder(newRow(), star(null, null), ACTOR, NOW, true));
            assertEquals(404, error.getResponse().getStatus());
        }
    }

    /**
     * A {@code COLLEAGUE} row is one of ours, kept in the registry only so the rebuild
     * remembers the name is not a client contact. Starring one would put a Trustworks
     * consultant on a client's plan as their stakeholder — the defect the whole cut exists
     * to fix — so it answers exactly as a person from another account does.
     */
    @Test
    void oneOfOurOwnConsultantsCannotBeStarred() {
        try (MockedStatic<AccountPerson> registry = mockStatic(AccountPerson.class)) {
            registry.when(() -> AccountPerson.findOnClient(CLIENT, PERSON))
                    .thenReturn(person(AccountPersonKind.COLLEAGUE, "Sara Vest", "Senior konsulent"));

            WebApplicationException error = assertThrows(WebApplicationException.class,
                    () -> service.applyStakeholder(newRow(), star(null, null), ACTOR, NOW, true));
            assertEquals(404, error.getResponse().getStatus());
        }
    }

    /**
     * A seat typed into the plan, or a name promoted from a signal, names no person — and
     * must not cost a registry read on every keystroke-level save.
     */
    @Test
    void aBodyWithNoPersonNeverTouchesTheRegistry() {
        ClientPlanStakeholder row = newRow();
        try (MockedStatic<AccountPerson> registry = mockStatic(AccountPerson.class)) {
            service.applyStakeholder(row, seat("Programme director"), ACTOR, NOW, true);
            registry.verifyNoInteractions();
        }
        assertNull(row.getPersonUuid());
    }

    // ------------------------------------------------------------------------
    // unit: the placeholder that rendered as a real org unit
    // ------------------------------------------------------------------------

    /**
     * {@code unit} is NULL-able since V606 and the old {@code "—"} default is gone. A
     * starred person has no org unit — the sources give a name and sometimes a job title —
     * and a placeholder here is what made the tab render "CIO, " with nothing after the
     * comma. Blank input lands as null too, so "no unit" and "somebody typed a space" are
     * the same state.
     */
    @Test
    void unitIsStoredAsNullRatherThanAnEmDashPlaceholder() {
        ClientPlanStakeholder nothing = newRow();
        service.applyStakeholder(nothing, seatWithUnit(null), ACTOR, NOW, true);
        assertNull(nothing.getUnit(), "a create with no unit stores NULL, not an em dash");

        ClientPlanStakeholder blank = newRow();
        service.applyStakeholder(blank, seatWithUnit("   "), ACTOR, NOW, true);
        assertNull(blank.getUnit(), "and neither does a blank one");

        ClientPlanStakeholder typed = newRow();
        service.applyStakeholder(typed, seatWithUnit("  IT Operations "), ACTOR, NOW, true);
        assertEquals("IT Operations", typed.getUnit(), "a real unit is still kept, trimmed");
    }

    /** The hand-typed seat keeps every default it had: title, buying role and influence. */
    @Test
    void aHandTypedSeatStillGetsItsOldDefaults() {
        ClientPlanStakeholder row = newRow();
        service.applyStakeholder(row, seat("Programme director"), ACTOR, NOW, true);

        assertEquals("—", row.getTitle());
        assertEquals(BuyingRole.USER_BUYER, row.getBuying());
        assertEquals(Influence.MEDIUM, row.getInfluence());
    }

    /** A row with neither a name nor a seat cannot be found again, so it is refused. */
    @Test
    void aRowWithNeitherANameNorASeatIsRefused() {
        WebApplicationException error = assertThrows(WebApplicationException.class,
                () -> service.applyStakeholder(newRow(), seat(null), ACTOR, NOW, true));
        assertEquals(400, error.getResponse().getStatus());
    }

    /**
     * "Still right" leans on this: any edit at all re-dates the row, including one whose
     * body changed nothing and one with no body at all. Somebody just looked at the person
     * and said they are still there, which is the whole claim {@code validated_at} makes.
     */
    @Test
    void everyEditReStampsValidatedAtIncludingAnEmptyOne() {
        ClientPlanStakeholder row = newRow();
        row.setName("Sara Louise Vest");
        row.setValidatedAt(NOW.minusMonths(8));

        service.applyStakeholder(row, null, ACTOR, NOW, false);

        assertEquals(NOW, row.getValidatedAt());
        assertEquals(NOW, row.getModifiedAt());
        assertEquals(ACTOR, row.getModifiedBy());
    }

    // ------------------------------------------------------------------------
    // Relations: the target, and the duplicate that used to be a 500
    // ------------------------------------------------------------------------

    /**
     * {@code uq_plan_relation (stakeholder_uuid, user_uuid)} is a unique key and nothing
     * de-duplicated the incoming list: a body naming one colleague twice deleted every
     * relation and then failed at flush with a constraint violation surfacing as a 500.
     * {@code replaceObjectiveLeads} has called {@code .distinct()} since it was written;
     * this is the same rule, keyed on the column the index actually covers.
     */
    @Test
    void twoEntriesForTheSameColleagueBecomeOneRelation() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<ClientPlanRelation> rows = interceptRelations()) {
            stubUsersExist(panache);

            service.replaceRelations(STAKEHOLDER,
                    Arrays.asList(relation(TOMMY, 3, "PRIMARY"), relation(TOMMY, 1, "SUPPORTING"),
                            relation(LUKAS, 2, null)),
                    ACTOR, NOW);
        }

        assertEquals(2, relationsBuilt.size(), "one row per colleague, whatever the body repeats");
        assertEquals(TOMMY, relationsBuilt.get(0).getUserUuid());
        assertEquals(3, relationsBuilt.get(0).getTargetLevel(), "the first entry for a colleague wins");
        assertEquals(RelationRole.PRIMARY, relationsBuilt.get(0).getRole());
        assertEquals(LUKAS, relationsBuilt.get(1).getUserUuid());
        assertEquals(RelationRole.SUPPORTING, relationsBuilt.get(1).getRole(), "the default role");
    }

    /**
     * The target is 0-4 and the assessor is the caller. A 9 would draw a bar wider than its
     * track and read as a stronger aim than the scale can express; an {@code assessedBy}
     * taken from the body would be a number attributed to somebody who never set it.
     */
    @Test
    void theTargetIsClampedAndTheServerStampsWhoSetIt() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<ClientPlanRelation> rows = interceptRelations()) {
            stubUsersExist(panache);

            service.replaceRelations(STAKEHOLDER, List.of(relation(TOMMY, 9, "primary")), ACTOR, NOW);
        }

        assertEquals(1, relationsBuilt.size());
        ClientPlanRelation row = relationsBuilt.get(0);
        assertEquals(4, row.getTargetLevel());
        assertEquals(ACTOR, row.getAssessedBy());
        assertEquals(NOW, row.getAssessedAt());
        assertEquals(STAKEHOLDER, row.getStakeholderUuid());
        assertNotNull(row.getUuid(), "every row carries its own uuid");
    }

    /** A null or blank entry is skipped rather than refused — the set is replaced either way. */
    @Test
    void emptyEntriesAreSkippedAndAnAbsentListWritesNothing() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<ClientPlanRelation> rows = interceptRelations()) {
            stubUsersExist(panache);

            service.replaceRelations(STAKEHOLDER,
                    Arrays.asList(null, relation("   ", 2, null)), ACTOR, NOW);
            service.replaceRelations(STAKEHOLDER, null, ACTOR, NOW);
        }

        assertTrue(relationsBuilt.isEmpty());
    }

    // ------------------------------------------------------------------------
    // The plan a star starts
    // ------------------------------------------------------------------------

    /**
     * Ninety days, pinned. It is the first {@code next_review} default the backend has ever
     * had — the column is {@code DATE NULL} with no DDL default and every other write path
     * stores exactly what the caller sent — so it is only a number in one place, and the two
     * frontend dialogs that seed the same ninety days have to keep agreeing with it.
     */
    @Test
    void aStarStartsAPlanWhoseFirstReviewIsNinetyDaysOut() {
        assertEquals(90, AccountPlanService.STARRED_PLAN_FIRST_REVIEW_DAYS);
    }

    // ------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------

    /** A stakeholder row as {@code addStakeholder} hands it to {@code applyStakeholder}. */
    private static ClientPlanStakeholder newRow() {
        ClientPlanStakeholder row = new ClientPlanStakeholder();
        row.setUuid(STAKEHOLDER);
        row.setClientUuid(CLIENT);
        row.setCreatedAt(NOW);
        row.setCreatedBy(ACTOR);
        return row;
    }

    private static AccountPerson person(AccountPersonKind kind, String name, String title) {
        AccountPerson person = new AccountPerson();
        person.setUuid(PERSON);
        person.setClientUuid(CLIENT);
        person.setName(name);
        person.setTitle(title);
        person.setKind(kind);
        return person;
    }

    /** What the star popover sends: a person, a buying role, an influence — and nothing else. */
    private static PlanRequests.StakeholderRequest star(String name, String title) {
        return new PlanRequests.StakeholderRequest(
                PERSON, name, null, title, null, "DECISION_MAKER", "HIGH", null, null);
    }

    private static PlanRequests.StakeholderRequest seat(String roleLabel) {
        return new PlanRequests.StakeholderRequest(
                null, null, roleLabel, null, null, null, null, null, null);
    }

    private static PlanRequests.StakeholderRequest seatWithUnit(String unit) {
        return new PlanRequests.StakeholderRequest(
                null, "Sara Louise Vest", null, null, unit, null, null, null, null);
    }

    private static PlanRequests.StakeholderRequest.RelationRequest relation(
            String userUuid, int target, String role) {
        return new PlanRequests.StakeholderRequest.RelationRequest(userUuid, target, role);
    }

    /** Every colleague named in a test exists; {@code requireUser} is not what is under test. */
    private void stubUsersExist(MockedStatic<PanacheEntityBase> panache) {
        panache.when(() -> PanacheEntityBase.findById(anyString())).thenAnswer(invocation -> new User());
    }

    /**
     * Relation rows with real setters and a {@code persist()} that does nothing, so the test
     * can read back exactly what the service decided without a session behind it.
     */
    private MockedConstruction<ClientPlanRelation> interceptRelations() {
        return mockConstruction(ClientPlanRelation.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS),
                (row, context) -> {
                    relationsBuilt.add(row);
                    doNothing().when(row).persist();
                });
    }
}
