package dk.trustworks.intranet.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gate for the cross-repo catalogue artifact (Phase 5, task 5.2).
 *
 * <p>{@code docs/access/permission-catalogue.json} is vendored by the frontend
 * and drives its generated {@code Permission} string-literal union. This test
 * regenerates the artifact from {@link Permissions#CATALOGUE} and asserts the
 * committed file matches byte for byte. It runs in the fast tier, which gates
 * every deploy (Phase 2), so a catalogue change cannot ship without the artifact
 * — and with it the frontend union — being regenerated deliberately.
 */
class PermissionCatalogueArtifactTest {

    @Test
    void committedArtifactMatchesCatalogue() throws IOException {
        Path artifact = Path.of(PermissionCatalogueJson.ARTIFACT_FILE);
        assertTrue(Files.exists(artifact),
                "Missing " + artifact + " — generate it: ./mvnw -q compile && "
                        + "java -cp target/classes dk.trustworks.intranet.security.PermissionCatalogueJson");
        assertEquals(PermissionCatalogueJson.render(), Files.readString(artifact, StandardCharsets.UTF_8),
                "permission-catalogue.json has drifted from Permissions.java. Regenerate it (do NOT edit by hand): "
                        + "./mvnw -q compile && java -cp target/classes dk.trustworks.intranet.security.PermissionCatalogueJson"
                        + " — then re-vendor it into the frontend (see trustworks-intranet-v2 scripts/generate-permissions.mjs).");
    }
}
