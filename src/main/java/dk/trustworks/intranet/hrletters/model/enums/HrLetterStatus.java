package dk.trustworks.intranet.hrletters.model.enums;

/**
 * Lifecycle of an HR letter.
 *
 * <p>{@code DRAFT} → {@code SENT} (HR approves; PDF generated, filed and
 * announced on Slack) → {@code ACKNOWLEDGED} (the employee's read-receipt).
 * {@code DISMISSED} is the terminal no-op: HR discards a draft that should
 * not become a letter (corrections, duplicate rows) or rejects a vacation
 * request — or the employee withdraws their own pending request.</p>
 */
public enum HrLetterStatus {
    DRAFT,
    SENT,
    ACKNOWLEDGED,
    DISMISSED
}
