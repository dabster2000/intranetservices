-- Employee documents §6.5.3: a hire whose paperwork never came back signed
-- must be distinguishable from a hire whose promotion succeeded.
--
-- Before this, a candidate with no completed signing case promoted every
-- draft it could reach and landed COMPLETED, so "the file is full of the
-- wrong documents" and "the file is correct" were the same value. With the
-- selection rule corrected to signed-only, that candidate now promotes
-- nothing — which must not read as success either.
--
-- Terminal state, never re-driven by the nextsign-status-sync sweep.
ALTER TABLE recruitment_candidates
    MODIFY COLUMN promotion_status
    ENUM('PENDING','COMPLETED','FAILED','NO_BINDING_DOCUMENTS') NULL
    COMMENT 'S3->S3 promotion state; NULL = legacy SharePoint pipeline';
