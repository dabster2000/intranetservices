package dk.trustworks.intranet.recruitmentservice.airtable;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spec §10 step 1: export all tables of the recruitment base via the
 * Airtable API. Tables are discovered through the Meta API — the base's
 * per-team pipelines are tables, and hardcoding their names would break
 * the day a team renames one.
 * <p>
 * Attachment bytes are fetched separately ({@link #download(String)})
 * because Airtable attachment URLs are short-lived: the import job
 * downloads each file inside the run that discovered the URL.
 */
@JBossLog
@ApplicationScoped
public class AirtableExportService {

    private static final int PAGE_SIZE = 100;

    /** Attachment download cap — a CV bigger than this is not a CV. */
    static final int MAX_ATTACHMENT_BYTES = 30 * 1024 * 1024;

    @RestClient
    AirtableClient airtableClient;

    // Optional<String> injection is REQUIRED here: the yml values are
    // `${AIRTABLE_TOKEN:}` — present-but-empty when the env var is unset,
    // which converts to null and aborts startup with SRCFG00040 when
    // injected into a plain String (the cvtool.username trap documented
    // in application.yml; defaultValue does not rescue a present value).
    @ConfigProperty(name = "dk.trustworks.recruitment.airtable.token")
    java.util.Optional<String> token;

    @ConfigProperty(name = "dk.trustworks.recruitment.airtable.base-id")
    java.util.Optional<String> baseId;

    /**
     * Tables that are not candidate pipelines (lookup/config tables in the
     * base) — excluded from the export. Comma-separated names, optional.
     */
    @ConfigProperty(name = "dk.trustworks.recruitment.airtable.excluded-tables")
    java.util.Optional<String> excludedTables;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public boolean isConfigured() {
        return !token.orElse("").isBlank() && !baseId.orElse("").isBlank();
    }

    /** table name → raw records, every pipeline table, fully paged. */
    public Map<String, List<AirtableClient.AirtableRecord>> exportAllTables() {
        requireConfigured();
        List<String> excluded = List.of(excludedTables.orElse("").split(",")).stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(java.util.Locale.ROOT))
                .toList();

        Map<String, List<AirtableClient.AirtableRecord>> byTable = new LinkedHashMap<>();
        AirtableClient.TablesResponse tables = airtableClient.listTables(bearer(), baseId());
        if (tables == null || tables.tables() == null || tables.tables().isEmpty()) {
            throw new IllegalStateException("Airtable base " + baseId()
                    + " reports no tables — check the token's schema.bases:read scope");
        }
        for (AirtableClient.TableInfo table : tables.tables()) {
            if (excluded.contains(table.name().trim().toLowerCase(java.util.Locale.ROOT))) {
                log.infof("Airtable export: skipping excluded table '%s'", table.name());
                continue;
            }
            byTable.put(table.name(), fetchAll(table));
        }
        return byTable;
    }

    private List<AirtableClient.AirtableRecord> fetchAll(AirtableClient.TableInfo table) {
        List<AirtableClient.AirtableRecord> records = new ArrayList<>();
        String offset = null;
        do {
            AirtableClient.RecordsPage page =
                    airtableClient.listRecords(bearer(), baseId(), table.id(), PAGE_SIZE, offset);
            if (page == null || page.records() == null) {
                break;
            }
            records.addAll(page.records());
            offset = page.offset();
        } while (offset != null);
        log.infof("Airtable export: table '%s' → %d records", table.name(), records.size());
        return records;
    }

    /**
     * All comments on one record (the collaboration thread — interview
     * feedback, scheduling agreements), fully paged. Table NAME works in
     * the path (the API accepts id or name).
     */
    public List<AirtableClient.AirtableComment> fetchComments(String tableName, String recordId) {
        requireConfigured();
        List<AirtableClient.AirtableComment> comments = new ArrayList<>();
        String offset = null;
        do {
            AirtableClient.CommentsPage page = airtableClient.listComments(
                    bearer(), baseId(), tableName, recordId, PAGE_SIZE, offset);
            if (page == null || page.comments() == null) {
                break;
            }
            comments.addAll(page.comments());
            offset = page.offset();
        } while (offset != null);
        return comments;
    }

    /**
     * Download one attachment (expiring Airtable URL). Never called with a
     * URL from anywhere but the just-fetched export.
     */
    public byte[] download(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("Attachment download failed with HTTP " + response.statusCode());
        }
        byte[] bytes = response.body();
        if (bytes.length > MAX_ATTACHMENT_BYTES) {
            throw new IOException("Attachment exceeds " + MAX_ATTACHMENT_BYTES + " bytes: " + bytes.length);
        }
        return bytes;
    }

    private String bearer() {
        return "Bearer " + token.orElse("");
    }

    private String baseId() {
        return baseId.orElse("");
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new IllegalStateException(
                    "Airtable import is not configured — set dk.trustworks.recruitment.airtable.token "
                            + "(AIRTABLE_TOKEN) and dk.trustworks.recruitment.airtable.base-id (AIRTABLE_BASE_ID)");
        }
    }
}
