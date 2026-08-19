package dk.trustworks.intranet.recruitmentservice.services;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateRequest;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Both dossier-creating paths derive the template the same way, through
 * {@link DossierTemplateResolver}.
 *
 * <p>Pinned structurally because the alternative is invisible until it hurts.
 * The signer seeding lived as a {@code private} method on
 * {@link CandidateService} until the create-offer-dossier endpoint needed it
 * too, and two copies of a rule about the same field is precisely how the
 * {@code CandidateRequest.opensDossier()} bug happened — the resource asked
 * "is a template present?" with {@code trim()} and the service asked it with
 * {@code isBlank()}, the two disagreed on control characters, and an
 * intake-only caller walked into the contract flow.</p>
 *
 * <p>The resolve-and-require-active check is the same story from the other
 * side: {@code seedSignersFromTemplate} queries only the <em>child</em>
 * {@code template_default_signers} table, so without it a nonexistent
 * template is indistinguishable from a real one with no default signers.
 * Both paths must run it, so both are asserted here.</p>
 */
class DossierTemplateSingleSourceTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .importClasses(CandidateService.class, DossierService.class);

    @Test
    void candidateService_holdsNoSecondCopyOfTheSeeding() {
        Set<String> declared = Arrays.stream(CandidateService.class.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertFalse(declared.contains("seedSignersFromTemplate"),
                "the seeding rule lives on DossierTemplateResolver, which both create "
                        + "paths call — a private copy here is a rule that can drift");
    }

    @Test
    void theCandidateCreatePath_resolvesAndSeedsThroughTheResolver() {
        Set<String> callees = calleesOf(CandidateService.class, "createCandidate",
                CandidateRequest.class, UUID.class, RecruitmentPosition.class);

        assertTrue(callees.contains("DossierTemplateResolver#requireActiveTemplate"),
                "a templateUuid that does not resolve produces a dossier that looks fine "
                        + "and renders blank placeholders into a real contract; "
                        + "actual callees: " + callees);
        assertTrue(callees.contains("DossierTemplateResolver#seedSignersFromTemplate"),
                "actual callees: " + callees);
    }

    @Test
    void theCreateDossierPath_seedsThroughTheSameResolver() {
        Set<String> callees = calleesOf(DossierService.class, "createForCandidate",
                UUID.class, String.class, UUID.class);

        assertTrue(callees.contains("DossierTemplateResolver#seedSignersFromTemplate"),
                "actual callees: " + callees);
        assertTrue(callees.contains("DossierService#resolveReopenTarget"),
                "the guard chain — including the template check — belongs to one method; "
                        + "actual callees: " + callees);
    }

    /** Every method this method calls, as {@code SimpleOwner#name}. */
    private static Set<String> calleesOf(Class<?> owner, String methodName, Class<?>... params) {
        JavaMethod method = CLASSES.get(owner).getMethod(methodName, params);
        return method.getMethodCallsFromSelf().stream()
                .map(JavaMethodCall::getTarget)
                .map(target -> target.getOwner().getSimpleName() + "#" + target.getName())
                .collect(Collectors.toSet());
    }
}
