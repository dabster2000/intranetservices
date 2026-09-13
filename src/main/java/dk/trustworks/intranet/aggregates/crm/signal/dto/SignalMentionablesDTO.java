package dk.trustworks.intranet.aggregates.crm.signal.dto;

import java.util.List;

/**
 * Everything the capture panel's {@code @} picker can resolve: clients and colleagues.
 *
 * <p>One endpoint rather than two, and a dedicated one rather than a reuse, for reasons
 * that are not interchangeable:
 *
 * <ul>
 *   <li><b>It is the same list the extractor gets.</b> Both sides are built from
 *       {@code AccountSignalService.clientAllowlist()} / {@code colleagueAllowlist()}, so
 *       the picker can never offer something the model is then told does not exist, and
 *       the model can never return a uuid the picker could not have produced.</li>
 *   <li><b>It never serialises a {@code User}.</b> Reusing {@code /users} would send whole
 *       User rows to the BFF, and the BFF's own token carries {@code admin:*} — which makes
 *       {@code UserScopeResponseFilter} inert and ships salaries and bank details to the
 *       browser to autocomplete a name. Two strings per person is all a typeahead needs.</li>
 *   <li><b>Clients are NOT contract-scoped.</b> {@code /clients} is scoped to the caller's
 *       active contracts; an employee most often hears something about a company Trustworks
 *       does not serve yet, and that route would find nothing exactly then.</li>
 * </ul>
 *
 * @param clients    every client, {@code {uuid, name}}, sorted by name
 * @param colleagues every currently employed colleague, {@code {uuid, name}}, sorted by name
 */
public record SignalMentionablesDTO(List<MentionableDTO> clients, List<MentionableDTO> colleagues) {

    /** A thing the {@code @} picker can insert: an id and the text that is typed for it. */
    public record MentionableDTO(String uuid, String name) {
    }
}
