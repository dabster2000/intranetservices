package dk.trustworks.intranet.aggregates.bugreport.dto;

import java.util.List;

/**
 * DTO for the deterministic policy engine output.
 * Describes the routing decision, risk assessment, and any security flags
 * for an auto-fix request. Used internally for gating and audit logging.
 *
 * @see dk.trustworks.intranet.aggregates.bugreport.services.AutoFixPolicyEngine
 */
public class PolicyDecision {

    private String routeDecision;
    private double riskScore;
    private List<String> reasons;
    private List<String> securityFlags;
    private boolean allowAutoFix;

    /**
     * Creates an approval decision for an ordinary bug fix.
     */
    public static PolicyDecision approve(String reason) {
        var d = new PolicyDecision();
        d.routeDecision = "ordinary_bug";
        d.riskScore = 0.0;
        d.reasons = List.of(reason);
        d.securityFlags = List.of();
        d.allowAutoFix = true;
        return d;
    }

    /**
     * Creates a rejection decision with detailed routing and risk information.
     */
    public static PolicyDecision reject(String route, double risk, List<String> reasons, List<String> flags) {
        var d = new PolicyDecision();
        d.routeDecision = route;
        d.riskScore = risk;
        d.reasons = reasons;
        d.securityFlags = flags;
        d.allowAutoFix = false;
        return d;
    }

    // Getters and setters

    public String getRouteDecision() { return routeDecision; }
    public void setRouteDecision(String routeDecision) { this.routeDecision = routeDecision; }

    public double getRiskScore() { return riskScore; }
    public void setRiskScore(double riskScore) { this.riskScore = riskScore; }

    public List<String> getReasons() { return reasons; }
    public void setReasons(List<String> reasons) { this.reasons = reasons; }

    public List<String> getSecurityFlags() { return securityFlags; }
    public void setSecurityFlags(List<String> securityFlags) { this.securityFlags = securityFlags; }

    public boolean isAllowAutoFix() { return allowAutoFix; }
    public void setAllowAutoFix(boolean allowAutoFix) { this.allowAutoFix = allowAutoFix; }
}
