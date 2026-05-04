package dk.trustworks.intranet.signing.ports;

public class SigningCaseNotFoundException extends RuntimeException {
    public SigningCaseNotFoundException(String caseKey) {
        super("Signing case not found: " + caseKey);
    }
}
