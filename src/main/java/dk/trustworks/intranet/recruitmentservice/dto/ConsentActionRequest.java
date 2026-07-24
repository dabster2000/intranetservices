package dk.trustworks.intranet.recruitmentservice.dto;

/**
 * Body of {@code POST /consent/{token}} (ATS P19): the candidate's
 * decision on the public consent page. {@code action} is
 * {@code "GRANT"} or {@code "WITHDRAW"} — anything else answers 400.
 */
public class ConsentActionRequest {

    public String action;
}
