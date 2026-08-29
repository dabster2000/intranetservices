package dk.trustworks.intranet.aggregates.finance.dto.cxo;

/**
 * One service line on the industry service-line trend chart.
 *
 * @param code         service line id as stored on the financial facts, e.g. "TECH"
 * @param displayName  practice display name when the code maps to a practice,
 *                     otherwise the code itself
 */
public record ServiceLineMetaDTO(
        String code,
        String displayName
) {}
