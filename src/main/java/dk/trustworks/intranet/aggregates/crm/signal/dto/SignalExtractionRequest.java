package dk.trustworks.intranet.aggregates.crm.signal.dto;

/**
 * The body of {@code POST /account-signals/extract} — one free-text line to read.
 *
 * @param text       the line the author is typing; required
 * @param clientUuid the client the author already picked with {@code @}, or null.
 *                   When present it WINS over anything the model proposes: the picker is
 *                   deterministic, the model is not.
 */
public record SignalExtractionRequest(String text, String clientUuid) {
}
