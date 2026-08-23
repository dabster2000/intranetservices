package dk.trustworks.intranet.documentservice.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the fail-closed legacy-classification migration contract. */
class TemplateUsageMigrationStructureTest {

    @Test
    void migrationDefaultsToDossierPreservesKnownLifecycleTemplatesAndResticksDossiers()
            throws IOException {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V527__Classify_document_templates_by_usage.sql"));

        assertTrue(sql.contains("DEFAULT 'RECRUITMENT_DOSSIER'"));
        assertTrue(sql.contains("LOWER(name) LIKE '%lønregulering%'"));
        assertTrue(sql.contains("LOWER(name) LIKE '%fratrædelseserklæring%'"));
        assertTrue(!sql.contains("category <> 'EMPLOYMENT'"),
                "unverified categories must retain the fail-closed dossier default");
        assertTrue(sql.contains("FROM candidate_dossiers d"));
        assertTrue(sql.lastIndexOf("SET template_usage = 'RECRUITMENT_DOSSIER'")
                        > sql.indexOf("SET template_usage = 'EMPLOYEE_SIGNING'"),
                "candidate dossier references must win after lifecycle classification");
        assertTrue(sql.contains("CHECK (template_usage IN"));
    }
}
