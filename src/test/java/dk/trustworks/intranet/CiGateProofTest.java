package dk.trustworks.intranet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * TRANSIENT — task 2.7 of the authorization-model-unification plan.
 *
 * Deliberately failing, pushed to staging only to prove that the deploy workflow
 * stops at the fast-tier test step and that no image reaches ECR and no ECS
 * deployment starts. Reverted in the immediately following commit. If you are
 * reading this on any branch, that revert did not happen — delete this file.
 *
 * Plain JUnit, no @QuarkusTest, so it lands in the fast tier the gate runs.
 */
class CiGateProofTest {

    @Test
    void fails_on_purpose_so_the_deploy_workflow_must_stop_here() {
        assertEquals("and it must never reach ECR", "the gate should stop this deploy");
    }
}
