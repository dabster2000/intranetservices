-- V315 — align recruitment table collations with the rest of the schema.
--
-- V311-V314 created the four recruitment tables with COLLATE=utf8mb4_unicode_ci
-- (the default the SQL files specified explicitly). Legacy tables on this
-- database (signing_cases, users, companies, document_templates, ...) use
-- utf8mb4_general_ci, so any JOIN that compares a VARCHAR column from a
-- recruitment table against a VARCHAR column on a legacy table fails with:
--
--   ERROR 1267-HY000: Illegal mix of collations
--   (utf8mb4_unicode_ci,IMPLICIT) and (utf8mb4_general_ci,IMPLICIT)
--   for operation '='
--
-- This surfaced at runtime in RecruitmentSignatureCompletionListener, whose
-- batchlet joins candidate_dossier_revisions.signing_case_key against
-- signing_cases.case_key. Aligning the entire recruitment schema here is
-- safer than scattering COLLATE clauses across query sites and future-
-- proofs other JOINs (target_company_uuid, converted_user_uuid, etc.).
--
-- These tables contain no production data on staging beyond what was just
-- created, so the conversion is fast. CONVERT TO CHARACTER SET re-encodes
-- VARCHAR/TEXT/CHAR columns and rewrites the table-level default.

ALTER TABLE recruitment_candidates
  CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

ALTER TABLE candidate_dossiers
  CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

ALTER TABLE candidate_dossier_revisions
  CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

ALTER TABLE candidate_dossier_appendices
  CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
