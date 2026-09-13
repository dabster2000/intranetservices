package dk.trustworks.intranet.aggregates.crm.news;

import java.time.Instant;
import java.util.List;

/** A stored, source-attributed reading. GET never contacts the news provider. */
public record ClientNewsDTO(boolean eligible, Status status, boolean coverageLimited, Instant lastAttemptAt,
                            Instant lastSuccessfulCheckAt, List<Item> items) {
    public enum Status { PENDING, READY, FAILED, DISABLED }
    public enum Scope { COMPANY, GROUP }
    public enum Category {
        LEADERSHIP, STRATEGY, DIGITAL_TRANSFORMATION, INVESTMENT, PARTNERSHIP,
        RESTRUCTURING, REGULATION, OTHER
    }
    public record Item(String id, String title, String url, String source, Instant publishedAt,
                       String summary, Category category, Scope scope) { }
    /** Internal deduplication metadata is never included in the API response. */
    public record StoredItem(Item item, String storyKey) { }
}
