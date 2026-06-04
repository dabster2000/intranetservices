package dk.trustworks.intranet.aggregates.invoice.model.enums;

/** How a phantom-label client suggestion was produced. */
public enum SuggestionMethod {
    EXACT,    // case-insensitive exact match on the prefix-stripped label
    FUZZY,    // Jaro-Winkler match (>= ClientService threshold)
    CONTAINS, // last-resort substring/contains match
    NONE      // no candidate found
}
