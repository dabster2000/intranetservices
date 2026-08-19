package dk.trustworks.intranet.recruitmentservice.resources;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import dk.trustworks.intranet.recruitmentservice.dto.DossierCreateRequest;
import dk.trustworks.intranet.recruitmentservice.services.DossierService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wiring of {@code POST /candidates/{uuid}/dossier} — the manual HR step
 * that gives an existing candidate their offer dossier.
 *
 * <p>Three properties, all of which a behavioural test would need a database
 * to reach, and all of which are silent when they regress:</p>
 * <ol>
 *   <li><b>The two gates run, in order.</b> {@code enforceFlag} keeps the
 *       endpoint dark with the rest of the dossier feature;
 *       {@code requireDossierWritable} is the ADMIN/HR object-level gate —
 *       the scope alone is not enough, because the BFF's token carries
 *       {@code admin:*}. A create endpoint that used the READ gate (or none)
 *       would hand the contract surface to every team lead.</li>
 *   <li><b>The command is delegated.</b> The insert and the
 *       {@code DOSSIER_CREATED} append must happen in ONE transaction, and
 *       this resource carries none (nor may it — see
 *       {@code RecruitmentAtomicCreateStructureTest}), so composing them here
 *       would commit a dossier the timeline never mentions.</li>
 *   <li><b>The scope is the write scope.</b></li>
 * </ol>
 */
class RecruitmentResourceCreateDossierEndpointTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .importClasses(RecruitmentResource.class);

    @Test
    void createDossier_isAPostOnTheCandidateDossierPath() {
        Method endpoint = createDossierMethod();

        assertNotNull(endpoint.getAnnotation(POST.class), "must be @POST");
        Path path = endpoint.getAnnotation(Path.class);
        assertNotNull(path);
        assertEquals("/candidates/{uuid}/dossier", path.value(),
                "the create shares the GET/PUT path — the frontend's dossier route file "
                        + "gains a POST handler rather than a new route");
    }

    @Test
    void createDossier_requiresTheWriteScope() {
        RolesAllowed roles = createDossierMethod().getAnnotation(RolesAllowed.class);

        assertNotNull(roles, "class-level recruitment:read is not enough for a mutation");
        assertTrue(Set.of(roles.value()).contains("recruitment:write"),
                "must require recruitment:write; actual: " + Set.of(roles.value()));
    }

    @Test
    void createDossier_runsTheFlagAndTheWriteGate() {
        Set<String> callees = calleesOf("createDossier", UUID.class, DossierCreateRequest.class);

        assertTrue(callees.contains("RecruitmentResource#enforceFlag"),
                "every dossier endpoint gates on recruitment.dossier.enabled; "
                        + "actual callees: " + callees);
        assertTrue(callees.contains("RecruitmentResource#requireDossierWritable"),
                "creating the contract dossier is an ADMIN/HR act — the read gate would "
                        + "let the hiring owner create one; actual callees: " + callees);
        assertFalse(callees.contains("RecruitmentResource#requireVisibleCandidate"),
                "the candidate-profile tier includes TEAMLEAD, which decision D17 denies "
                        + "the contract flow; actual callees: " + callees);
    }

    @Test
    void createDossier_delegatesTheWholeCommandToTheService() {
        Set<String> callees = calleesOf("createDossier", UUID.class, DossierCreateRequest.class);

        assertTrue(callees.contains("DossierService#createForCandidate"),
                "actual callees: " + callees);
        assertFalse(callees.contains("CandidateDossier#persist"),
                "the insert belongs inside the service's transaction, together with the "
                        + "DOSSIER_CREATED append; actual callees: " + callees);
    }

    @Test
    void theCommandIsOneTransaction_soADossierCannotOutliveItsEvent() throws Exception {
        Method endpoint = createDossierMethod();
        Method command = DossierService.class.getMethod(
                "createForCandidate", UUID.class, String.class, UUID.class);

        assertNull(endpoint.getAnnotation(Transactional.class),
                "RecruitmentResource carries no transaction — that is what forces the "
                        + "insert and the event append into one service method");
        Transactional tx = command.getAnnotation(Transactional.class);
        assertNotNull(tx, "DossierService.createForCandidate must be @Transactional");
        assertEquals(Transactional.TxType.REQUIRED, tx.value(),
                "REQUIRES_NEW would commit the dossier independently of the caller");
    }

    private static Method createDossierMethod() {
        try {
            return RecruitmentResource.class.getMethod(
                    "createDossier", UUID.class, DossierCreateRequest.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(
                    "RecruitmentResource must expose createDossier(UUID, DossierCreateRequest)", e);
        }
    }

    /**
     * Every method this endpoint calls, as {@code SimpleOwner#name} — qualified
     * by owner because a bare name set cannot tell {@code URI.create(...)} from
     * a service {@code create(...)}. Same helper as
     * {@code RecruitmentAtomicCreateStructureTest}.
     */
    private static Set<String> calleesOf(String methodName, Class<?>... params) {
        JavaMethod method = CLASSES.get(RecruitmentResource.class).getMethod(methodName, params);
        return method.getMethodCallsFromSelf().stream()
                .map(JavaMethodCall::getTarget)
                .map(target -> target.getOwner().getSimpleName() + "#" + target.getName())
                .collect(Collectors.toSet());
    }
}
