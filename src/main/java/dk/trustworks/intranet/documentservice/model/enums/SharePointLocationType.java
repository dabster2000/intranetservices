package dk.trustworks.intranet.documentservice.model.enums;

/**
 * Classifies where in SharePoint a document associated with a template should be stored.
 * <p>
 * Combined with the user's active company, the type uniquely resolves a single
 * {@code sharepoint_locations} row at signing-case creation time, removing the need
 * for admins to maintain template-to-store links manually.
 * </p>
 */
public enum SharePointLocationType {
    EMPLOYEE, CLIENT, OTHER, NONE
}
