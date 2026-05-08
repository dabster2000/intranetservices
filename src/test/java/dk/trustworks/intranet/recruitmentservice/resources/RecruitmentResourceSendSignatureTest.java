package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.utils.services.SigningService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sanity checks for {@code POST /candidates/{uuid}/dossier/send-signature}
 * verifying that the {@link SigningService#saveMinimalCase} 5-arg overload is
 * still on the {@link SigningService} contract, so the {@code RecruitmentResource}
 * call site stays binary-compatible. End-to-end signing-case persistence is
 * exercised by CI integration tests; this test guards the static contract that
 * AC16 / AC17 / AC18 depend on without booting the Quarkus runtime.
 *
 * <p>AC16: {@code sendSignature} calls
 * {@code signingService.saveMinimalCase(caseKey, candidateUuid, documentName, totalSigners, null)}
 * exactly once, immediately after {@code createMultiDocumentSigningCase} returns
 * and BEFORE {@code dossierRevisionService.snapshotFromValues}.
 *
 * <p>AC17: The 5th argument is the literal {@code null} so the
 * {@code NextSignStatusSyncBatchlet} skip guard fires.
 *
 * <p>AC18 (contract level): the 5-arg overload exists and is public.
 */
class RecruitmentResourceSendSignatureTest {

    @Test
    void sendSignature_methodExists_andHasExpectedAnnotations() {
        Method m = findMethodByName("sendSignature");
        assertNotNull(m, "RecruitmentResource must expose sendSignature(...)");

        jakarta.ws.rs.POST post = m.getAnnotation(jakarta.ws.rs.POST.class);
        assertNotNull(post, "must be @POST");

        jakarta.ws.rs.Path methodPath = m.getAnnotation(jakarta.ws.rs.Path.class);
        assertNotNull(methodPath, "must have @Path");
        assertEquals("/candidates/{uuid}/dossier/send-signature", methodPath.value());

        jakarta.annotation.security.RolesAllowed roles =
                m.getAnnotation(jakarta.annotation.security.RolesAllowed.class);
        assertNotNull(roles, "must have @RolesAllowed");
        boolean hasWrite = false;
        for (String r : roles.value()) {
            if ("recruitment:write".equals(r)) hasWrite = true;
        }
        assertTrue(hasWrite, "must require recruitment:write");
    }

    @Test
    void signingService_saveMinimalCase_5argOverloadExists() throws Exception {
        Method m = SigningService.class.getMethod(
                "saveMinimalCase",
                String.class, String.class, String.class, int.class, String.class);
        assertNotNull(m,
                "SigningService.saveMinimalCase(caseKey,userUuid,documentName,totalSigners,sharepointLocationUuid) " +
                        "must exist — RecruitmentResource.sendSignature relies on this overload (AC16/AC17).");
        // 5th param of type String allows the literal null per AC17.
        assertEquals(String.class, m.getParameterTypes()[4],
                "5th parameter must be String to accept the literal null per AC17");
    }

    private static Method findMethodByName(String name) {
        for (Method m :
                dk.trustworks.intranet.recruitmentservice.resources.RecruitmentResource.class
                        .getDeclaredMethods()) {
            if (name.equals(m.getName())) return m;
        }
        return null;
    }
}
