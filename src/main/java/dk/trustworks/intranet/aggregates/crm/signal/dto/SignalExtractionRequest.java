package dk.trustworks.intranet.aggregates.crm.signal.dto;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The body of {@code POST /account-signals/extract} — one free-text line to read.
 *
 * <p>The picked clients are sent so the reading can be phrased against what the author
 * has already decided, and so the panel can tell a client the author picked apart from
 * one only the model noticed. They no longer SUPPRESS the model's own reading (V593):
 * before, a pick overrode it entirely, which is how a line naming two accounts could
 * never surface the one that was not picked last.
 *
 * @param clientUuid  legacy single pick; unioned with {@link #clientUuids}
 * @param clientUuids the clients the author has picked with {@code @} so far, or empty
 * @param text        the line the author is typing; required
 */
public record SignalExtractionRequest(String text, String clientUuid, List<String> clientUuids) {

    /** Every client the author has picked, de-duplicated, order preserved; never null. */
    public List<String> allClientUuids() {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (clientUuid != null && !clientUuid.isBlank()) {
            merged.add(clientUuid.trim());
        }
        if (clientUuids != null) {
            for (String uuid : clientUuids) {
                if (uuid != null && !uuid.isBlank()) {
                    merged.add(uuid.trim());
                }
            }
        }
        return new ArrayList<>(merged);
    }
}
