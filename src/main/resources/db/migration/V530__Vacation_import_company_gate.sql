-- Company-of-employment gate for the Danløn feriepengeforpligtelse import.
--
-- When an employee transfers between Trustworks A/S, Technology ApS and Cyber
-- Security ApS, payroll moves the *available* balance and drops the used
-- amount, so the receiving company's `Optjent dage` already contains the old
-- company's remainder. The old company's export still lists the person, with a
-- historical record that must not be imported. Until now nothing in the import
-- consulted `userstatus`, and which company's figures survived was decided
-- downstream by whichever batch had the later effective date — i.e. by the
-- order HR happened to upload the files in.
--
-- Two new row verdicts carry that decision: OTHER_COMPANY (employed elsewhere
-- on the batch's as-of date — skipped, HR may override) and UNKNOWN_COMPANY
-- (no employment record at that date — blocks the apply until HR resolves it).

-- UNKNOWN_COMPANY is 15 of the 16 available characters. It fits, but this is
-- an enum that is actively growing and one character of headroom is not a
-- margin; MariaDB in strict mode would reject the next one outright.
alter table vacation_import_rows
    modify column match_status varchar(32) not null;

-- The evidence behind an OTHER_COMPANY verdict, kept as a fact so it survives
-- an HR override: a verdict changes, what the timeline said does not.
alter table vacation_import_rows
    add column company_at_asof varchar(36) null after match_status;
