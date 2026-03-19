package dk.trustworks.intranet.userservice.model.enums;

/**
 * @deprecated Use String-based role names backed by the role_definition database table instead.
 *             This enum is retained only for backward compatibility during migration.
 */
@Deprecated(since = "2026-03-11", forRemoval = true)
public enum RoleType {

    SYSTEM, APPLICATION, COMMUNICATIONS, DPO, HR, USER, EXTERNAL, EDITOR, TEAMLEAD, SALES, ACCOUNTING, MARKETING, PARTNER, TECHPARTNER, CYBERPARTNER, TEMP, ADMIN

}
