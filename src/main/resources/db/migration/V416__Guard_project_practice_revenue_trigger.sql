-- =====================================================================================================
-- Guard trg_project_practice_revenue_au against no-op and locked-only updates.
--
-- Incident 2026-07-18: the nightly project-lock job (UPDATE project SET locked = TRUE, 578 rows)
-- fired the unguarded AFTER UPDATE trigger twice per row (1,156 calls). While the practice revenue
-- publication was UNINITIALIZED each call short-circuited cheaply, but once the publication went
-- PUBLISHED (2026-07-17 activation) every call opened the dependency cursor join in
-- sp_mark_practice_revenue_dependency_changed. The bulk update ran past the 600s transaction
-- timeout, was reaper-aborted, held shared locks on practice_revenue_publication for 10 minutes
-- (starving the per-minute delivery-evidence pollers with lock-wait timeouts), and mass-bumped the
-- DELIVERY_EVIDENCE source watermark — spuriously invalidating the practices revenue publication.
--
-- The revenue build consumes project rows only through their uuid (join key); `locked` is a pure
-- editing flag that can never affect delivery evidence. The recreated trigger therefore fires only
-- when a column other than `locked` actually changes, and marks the dependency once — twice only in
-- the (theoretical) case that the uuid itself is rewritten. INSERT/DELETE triggers are unchanged.
--
-- Idempotent (DROP IF EXISTS + CREATE) so repair-at-start re-runs are safe.
-- =====================================================================================================

DROP TRIGGER IF EXISTS trg_project_practice_revenue_au;

DELIMITER $$

CREATE TRIGGER trg_project_practice_revenue_au AFTER UPDATE ON project FOR EACH ROW
BEGIN
    IF NOT (OLD.uuid <=> NEW.uuid)
       OR NOT (OLD.active <=> NEW.active)
       OR NOT (OLD.budget <=> NEW.budget)
       OR NOT (OLD.clientuuid <=> NEW.clientuuid)
       OR NOT (OLD.created <=> NEW.created)
       OR NOT (OLD.customerreference <=> NEW.customerreference)
       OR NOT (OLD.name <=> NEW.name)
       OR NOT (OLD.userowneruuid <=> NEW.userowneruuid)
       OR NOT (OLD.startdate <=> NEW.startdate)
       OR NOT (OLD.enddate <=> NEW.enddate) THEN
        CALL sp_mark_practice_revenue_dependency_changed('DELIVERY_EVIDENCE', 'PROJECT', OLD.uuid);
        IF NOT (OLD.uuid <=> NEW.uuid) THEN
            CALL sp_mark_practice_revenue_dependency_changed('DELIVERY_EVIDENCE', 'PROJECT', NEW.uuid);
        END IF;
    END IF;
END$$

DELIMITER ;
