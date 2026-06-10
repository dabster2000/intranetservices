package dk.trustworks.intranet.aggregates.invoice.selfbilled.parse;

/** Pure value type: the parsed fields of one self-billed debtor line. */
public record ParsedLine(String faktura, int workYear, int workMonth, String code) {}
