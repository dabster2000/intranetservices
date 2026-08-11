-- V489: recruitment_candidates.created_by_useruuid is a SOFT reference —
-- drop the hard FK that only production still carries.
--
-- The ATS design treats created_by_useruuid as a marker-capable soft FK:
-- the P5 public forms write 'public-form' and the P21 Airtable importer
-- writes 'airtable-import' (PublicApplyService.PUBLIC_FORM_CREATOR
-- javadoc: "NOT NULL, soft FK — no DB constraint, nothing parses it as a
-- UUID"). Production, however, still has the dossier-era
-- fk_recruitment_candidates_creator_user (user(uuid), ON DELETE CASCADE)
-- — staging does not, which is why every staging rehearsal passed while
-- the first real production import failed with error 1452. The same
-- constraint would break the public /apply flow the day the pipeline
-- flag opens it. The CASCADE was also wrong for markers-or-people
-- semantics: deleting a user must never delete the candidates they
-- registered.
--
-- fk_recruitment_candidates_converted_user stays — converted_user_uuid
-- is always a real user row (set only by the conversion flow).
--
-- IF EXISTS keeps this idempotent for repair-at-start re-runs and for
-- staging, where the constraint is already absent.

ALTER TABLE recruitment_candidates
    DROP FOREIGN KEY IF EXISTS fk_recruitment_candidates_creator_user;
