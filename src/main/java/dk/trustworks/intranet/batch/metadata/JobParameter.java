package dk.trustworks.intranet.batch.metadata;

/**
 * Represents a parameter definition for a batch job.
 *
 * @param name The parameter name (as used in job XML or batchlet code)
 * @param type The parameter data type
 * @param required Whether this parameter is mandatory
 * @param defaultValue The default value if not provided (null if none)
 * @param description Human-readable description of what this parameter does
 */
public record JobParameter(
    String name,
    ParameterType type,
    boolean required,
    String defaultValue,
    String description
) {

    public enum ParameterType {
        STRING,
        DATE,       // ISO-8601 format (yyyy-MM-dd)
        INTEGER,
        BOOLEAN
    }
}
