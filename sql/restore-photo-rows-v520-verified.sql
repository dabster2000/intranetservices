-- ===================================================================
-- Restore of the real image rows V520__Remove_photo_placeholder_rows.sql deleted.
-- Generated 2026-08-20 by cross-checking three sources:
--   1. tw-db2-v520-recovery — PITR of trustworks-db2 @ 2026-08-19T21:47:00Z (pre-DELETE):
--      the 279 deleted rows whose relateduuid is a live user/client/team/course/
--      projectdescription row.
--   2. Full listing of s3://trustworksfiles (2026-08-20): kept ONLY the rows whose
--      files.uuid owns an object (the check V520 skipped). 193 of 279 qualify;
--      the other 84 were genuine read-miss placeholders with no bytes — correctly
--      deleted, NOT restored here. Spot-checked objects are real JPEG/PNG data.
--   3. Current prod rows: none of the 193 relateduuids holds a PHOTO row today,
--      so no restore can collide with a re-upload (MARIUS PEDERSEN A/S and
--      Junior Team already have real rows again and are excluded).
-- Breakdown: 7 team, 81 client, 88 user, 17 course. Zero projectdescriptions
-- qualified. Team HiTech's deleted row owned no bytes — it never had a logo;
-- upload one via Team Dashboard -> Change logo.
--
-- NOT a Flyway migration. Run interactively with an admin (write) connection
-- against prod `twservices4`; verify the checks, then COMMIT. Idempotent:
-- the NOT EXISTS guard makes a re-run insert nothing.
-- After COMMIT: force a new deployment of the backend ECS service — the
-- photo caches have no expiry, so cached empty reads outlive the restore.
-- ===================================================================

START TRANSACTION;

-- Step 1 — remove the one stray placeholder written AFTER V520 by the outgoing
-- task during the blue/green overlap. It owns no S3 object and currently
-- shadows Sofie Damkjær Østensgård's real portrait row (restored in step 2).
DELETE FROM files
WHERE uuid = '4325192c-1295-4908-9457-2c241b476239'
  AND type = 'PHOTO' AND name IS NULL AND filename IS NULL AND uploaddate IS NULL;
SELECT ROW_COUNT() AS stray_placeholder_deleted;  -- expect 1

-- Step 2 — restore the 193 verified rows. Metadata stays NULL on purpose: it is
-- what the deleted rows carried, and the reader (PhotoService.findPhotoByRelatedUUID)
-- needs only relateduuid + type. A later upload supersedes the row normally.
INSERT INTO files (uuid, relateduuid, type, name, filename, uploaddate)
SELECT c.uuid, c.relateduuid, 'PHOTO', NULL, NULL, NULL
FROM (
    -- ---- team (7) ----
    SELECT '48b5c8d0-a56b-45b8-92db-ba1e09fd8222' AS uuid, '48b5c8d0-a56b-45b8-92db-ba1e09fd8222' AS relateduuid  -- Team ACE
    UNION ALL SELECT '41e60e62-a0bc-43aa-999f-cc468c0bb1da', '2fea1fd5-a9f1-4262-817e-908e6f20ca22'  -- Team it
    UNION ALL SELECT '6af82224-29ed-4ef8-b80d-a8b23259d055', '210b3a6b-9e7b-482b-beaf-09d94d156a8c'  -- Team Partner
    UNION ALL SELECT 'ec1776ef-5b59-46f9-8b64-d35a188098aa', '054e7310-1761-4b18-8ea3-ec7bac5364cd'  -- Team Puppet Masters
    UNION ALL SELECT 'b1a480d0-bc74-4c33-aff0-90474a71d134', '0a45209d-5286-48ee-9af1-674c9fe293a9'  -- Team Really Bad Ass
    UNION ALL SELECT '2f9cc935-0b3c-496d-8cb0-1fa2870f8996', '3ed48ed5-0e45-49f9-81c2-5bcec06a7950'  -- Team Tech it or Leave it
    UNION ALL SELECT 'ffe07367-9241-427c-a7a8-d9e32eb571bb', '1a0a6503-a277-4bf4-a71b-ef40d3480ab5'  -- Team Y
    -- ---- client (81) ----
    UNION ALL SELECT 'e906337d-3b5f-447c-805c-4d3fe769ba19', 'd5ab0935-04db-4abe-9105-e92c5583c834'  -- Andel
    UNION ALL SELECT '4d38482e-f534-49a5-8575-455a471f93f0', 'f7d360d4-2042-4561-a65f-6d3d3bf7c378'  -- AP PENSIONSSERVICE A/S
    UNION ALL SELECT 'f947b0f1-9ea2-4f47-884e-f93c8ced5663', '6e30a6d6-9542-417f-9b5e-234d4c3e3931'  -- Ascendis Pharma
    UNION ALL SELECT 'd25d0b6e-a754-4907-82f8-139de12a1b5c', '59bc465a-3ea0-4293-ad5f-a79bd81a906c'  -- Astra*
    UNION ALL SELECT '6f1ee6dd-f1f6-4e09-be9d-cfcf542a0490', '8520a00f-0570-4a39-8fb2-03b988108ec5'  -- Banedanmark
    UNION ALL SELECT '26819935-bd0b-4c8d-baf3-35b899fb7303', '32e00314-ab35-4bd0-986c-2e2b92d1ccb6'  -- Boligkontoret Danmark
    UNION ALL SELECT '1667c0aa-ef48-4f77-ab4e-2b78986c217d', '376261df-00b6-4222-8c59-780a206e91a6'  -- Bus & Tog
    UNION ALL SELECT '8968007b-2f02-48ab-91e7-3ef7ee2208b0', '775ad437-7c9e-48b0-b37f-9c81bebd684a'  -- D LINE A/S
    UNION ALL SELECT 'f6498cd2-b34a-4ce5-ac4d-e4564a399dc5', 'a992b9ee-16e3-40c0-8d55-28425e563df0'  -- Danica Pension
    UNION ALL SELECT 'ea10ac2a-8b38-43c5-8622-12aba347f94a', '878765d4-bbb8-497b-89e7-31f2595ce2a5'  -- Danmarks Apotekerforening
    UNION ALL SELECT '0ef83b25-1b96-4194-a84c-6321146e3c4f', 'fe1e4c3f-dba7-436c-a679-2b579afeca6b'  -- Danmarks Fængsler
    UNION ALL SELECT 'e01e6c13-5609-4a05-96ce-a973c6e2585f', 'cd8e2efc-f1cf-45a5-845c-4e4b10817be8'  -- Danmarks Miljøportal
    UNION ALL SELECT '04002b6d-afb8-4ee4-92c3-5e4d2d35a2f9', '8591a2b0-1000-4c84-81e2-b62f4c358c28'  -- Data inc
    UNION ALL SELECT '3a67f276-17a0-4c4b-abdf-939d398ac109', '31ea900a-dd85-49fb-b61e-43840c4df6f1'  -- DDB
    UNION ALL SELECT '03f7d4eb-1c3a-4f30-9dbb-caa3981b0b66', '82f8c315-f7fc-4250-ae98-e078c504cffd'  -- Det Kgl. Bibliotek
    UNION ALL SELECT '8545ac6c-17aa-4b08-b43b-11dd365be98e', '02889a90-63d3-4707-99e5-c2be3a1e710f'  -- Devoteam
    UNION ALL SELECT '7d2770df-38dd-4f22-88fe-bd011c71f1b7', '97bd21ef-0029-46b8-8801-eddf78a40b5d'  -- Digitaliseringsstyrelsen
    UNION ALL SELECT 'b60a8519-5514-40cf-bf06-8cb13a944914', '834b0000-05c6-40fa-bce5-d93847e66d7b'  -- Domstolsstyrelsen
    UNION ALL SELECT 'cb38156d-d1e4-4225-be53-575e41a53f3a', 'cd762da0-2766-4b2e-a21d-3ca0f82c7957'  -- DSB
    UNION ALL SELECT '19ddaa9d-57fe-4138-a439-670eae599a46', '4734ff94-48eb-4362-9b7f-53b30466c4ec'  -- e-nettet
    UNION ALL SELECT '58b84b31-7ca2-44b9-aa05-479849a1f422', '211569e2-c461-49f3-b3eb-0f1a1dc84bab'  -- Erhvervsstyrelsen
    UNION ALL SELECT '747c46b4-9e1f-4127-a62b-fde4703a6dfd', '8a5fc562-61ca-445c-97a4-8027b76dec60'  -- Exformatics
    UNION ALL SELECT '44a58a80-ca60-476c-92f2-1b577ab9f9dd', 'f7fbbda6-6da9-4688-88d2-0602c1e1a732'  -- Finansrådet
    UNION ALL SELECT 'ff243609-df3a-4d1c-84f7-087f6b76cc99', '83601701-99ac-4ae4-84d2-aca6d73e78cf'  -- FORCA A/S
    UNION ALL SELECT '708d2caa-7136-445c-ae05-cc53d8f06751', '9a477885-0f4f-41d4-b1e3-67e82ae5e14f'  -- Fåborg-Midtfyn kommune
    UNION ALL SELECT '101232cd-4e6b-40b9-a798-e475d427e4bc', '932be46d-d423-47a8-96a7-166eabe8bf17'  -- Færøerne
    UNION ALL SELECT 'dd12e014-ec6d-41aa-ab1e-7f72c388dc83', '0f23a5b1-4454-49ec-b26d-0d1ba82b85dc'  -- IC Companys
    UNION ALL SELECT 'f2dc2b6a-505f-4c00-84b2-440c2bae82d3', '89f47a55-b080-4165-9ae0-85857da2a1a4'  -- INDUSTRIENS PENSIONSFORSIKRING A/S
    UNION ALL SELECT 'cd710da3-14ca-4b47-91d0-713109c1ad71', '63a19b35-250e-4bb9-9e44-f7dd67f87bdf'  -- Inter American Development Bank
    UNION ALL SELECT 'db0e36d8-ccb9-41fb-ad63-32ebc7a3061a', 'a2cf418e-3858-46c4-ad82-0e7d36339285'  -- KL
    UNION ALL SELECT '2961b3b6-3635-447a-8ecb-5ad73b517a8b', '2aeeea8c-47a6-464a-92be-33f5d16ee9ee'  -- KMD 
    UNION ALL SELECT '0c7b2032-0dba-47ca-bf80-1052df32e605', '7d5e8dc7-4c5f-4467-adf2-d72ec884a9a2'  -- KOMBIT
    UNION ALL SELECT '26bc3748-5619-40e4-a600-4dc0fa856e53', '4fa50ce7-0d51-4312-b5fe-381f91d14f6b'  -- Kompagniet af 1991 A/S
    UNION ALL SELECT '89dd8828-3e4a-4b69-b0f1-892fecbef967', 'fb32ffb9-870c-4f26-9e8b-536579ef8f5d'  -- Københavns Kommune KFF
    UNION ALL SELECT '6c23cb48-e875-4e73-888f-cf893a1e64b2', '4e9f6701-3d1e-4541-b177-2615736df923'  -- KØBENHAVNS LUFTHAVNE A/S
    UNION ALL SELECT 'fd03703a-4215-4810-8f02-40d91a729550', '643e24d3-7f56-49b6-b4ad-1be45558b677'  -- Købstædernes Forsikring
    UNION ALL SELECT 'ec7685a7-5ec5-4274-a7c0-3868b9a93577', 'ba23f4a8-a911-47bc-bfeb-057c21fe3ad9'  -- Metroselskabet
    UNION ALL SELECT 'd2704175-51c7-4d84-ad61-54c65c47f116', '9a6b3353-b9fa-4928-890b-32962420319f'  -- Miinto
    UNION ALL SELECT '86ca4fc2-da90-4942-a8cd-4130a7b9e30c', '5d74d996-68c6-42a0-830d-3179dd3e4487'  -- Miljøstyrelsen
    UNION ALL SELECT 'ddc399ed-d5bc-4bf0-b671-07d89b61e6b1', '5d782c0c-fca9-4209-b724-17de76e1b904'  -- Movia
    UNION ALL SELECT '976eaca6-6054-421d-a90a-ce264754d3fd', 'bcb51b41-c12d-4c0d-aa06-5c53bd93a236'  -- Mærsk
    UNION ALL SELECT '7dbea64a-2d44-4ae2-a75a-8e80ce2cc0ff', 'fdd44197-e71a-4862-8de6-65d7bec55766'  -- Nationalbanken
    UNION ALL SELECT '845519a5-74a7-4464-b246-75f03ebc5a9e', '242bafb1-fa1c-40d6-af52-447bee5a7ab4'  -- Nationalt Genom Center
    UNION ALL SELECT '4cc6114b-44b8-4ba5-a8e1-51585cdfb313', '04322c67-6a51-48f1-b219-7593c0958381'  -- Nordea
    UNION ALL SELECT '5d7da68a-f007-4299-8168-d21ac0d7d815', '78b595a4-0bdc-4349-a762-61f013b4595e'  -- Nordisk Film TV
    UNION ALL SELECT 'a07a9b5b-5d8e-4ae6-b1d2-e2a0f4fa74a2', '2c468b54-5c97-429e-a1d0-53f894995a52'  -- Novo Holdings A/S
    UNION ALL SELECT 'c57a448b-00b5-4aae-aa2d-652ca307649e', '7b531672-e843-42c2-ba4e-3b5e0eece57b'  -- NOVO NORDISK A/S
    UNION ALL SELECT '38a248a4-2cc5-48a6-b177-9dab2356a9c5', '87c2632b-17d3-45d7-a77c-e0ad8a6e333a'  -- Novo Nordisk Foundation
    UNION ALL SELECT '2f705f1f-f664-4ece-9164-4932e0cf86c2', '56ee2cca-3665-4a55-a606-9f249bef82cd'  -- Nykredit
    UNION ALL SELECT '7f9129c2-c216-497f-b132-6184b306cd0b', '8435b712-2b48-45d7-8975-8bbac5b6d198'  -- Nærpension
    UNION ALL SELECT 'c82243e6-5cbb-491b-8060-b9a4144ee552', '1a441904-97ff-4aad-9e49-fb33bde66428'  -- Nævnenes Hus
    UNION ALL SELECT '71897528-619b-4862-bb3d-5322f2425705', '2e8a39df-cf5f-4fc5-9494-a48539a2b1c9'  -- Odense Kommune
    UNION ALL SELECT '995a9271-2884-4e62-af12-9490a5315975', 'db50884c-fdd2-4e3d-87dc-3adbdf132fb7'  -- PEAK Consulting A/S
    UNION ALL SELECT '30215cb4-7953-4bcf-985f-7fe19c94ad72', '1b339695-9d66-4bed-97ad-a6999d4cc3f6'  -- PFA
    UNION ALL SELECT '9fbf8b0e-c532-4cc6-bc6e-deaf5c192a49', 'bac76f9f-286b-4fcd-a6cb-8974b83204e9'  -- Region H IMT
    UNION ALL SELECT 'c72b2625-df55-49a9-a962-e023ef219338', '85f59f47-e316-4f62-b240-37a2738bedcd'  -- Region Midt
    UNION ALL SELECT 'c656a765-8555-470f-a309-79aa8c1c4ac4', 'b981f854-d0f1-4b2c-8c3b-05022b7cc200'  -- Region Sjælland
    UNION ALL SELECT '0b897fe4-7210-4d21-91a4-c0d416d2f3a5', '7d34db42-9d11-4fe2-84df-449a46cdfcc9'  -- Rigspolitiet
    UNION ALL SELECT '85cad052-b52d-4065-b31b-3ad950da0b3a', '16626eda-a241-43b0-9163-5151e97d99fe'  -- RISE Research Intstitute
    UNION ALL SELECT '9a13c2a6-cbe6-4a5b-b833-dedfffcfc29a', 'e227a160-2878-41a4-bec0-23a2cfbfb127'  -- Røde Kors
    UNION ALL SELECT '53466b9d-3fca-42ad-ba77-fc8bc982974d', '78294d04-4a3d-4456-a294-5cfca48270bc'  -- Saxo Bank
    UNION ALL SELECT '5600d45e-08ec-4106-9575-829c381f1508', 'df6e27b8-d2a2-4308-b016-17d6c072149d'  -- SimCorp
    UNION ALL SELECT '3a405204-1795-437a-af3e-ba33fdc63f73', '5c954034-d767-4ddf-8760-a2f5c7e0220b'  -- SKAT
    UNION ALL SELECT '48ef97a6-f624-417c-9b63-2bf38d753920', 'de488340-2fc7-499f-814e-cb1c536e1f49'  -- Social- og Boligstyrelsen
    UNION ALL SELECT '1c9b320d-d102-48b3-a388-c1aad5fc4d32', 'a47ed9de-e583-4d22-8f45-e8a10c8d021d'  -- Styrelsen for Dataforsyning og Effektivisering
    UNION ALL SELECT '15b4ffc1-a979-4d89-b978-c0ba4f9cf2b2', '1e2bf6bb-2a19-469e-bc81-668794a2b85c'  -- Styrelsen for Vand- og Natur- forvaltning
    UNION ALL SELECT 'f182b00b-89d2-4d2f-968c-1833da19ff55', '044b53c4-3b30-47f3-a581-516813c50cfc'  -- SVANA
    UNION ALL SELECT 'b4b6a2b8-a20b-4bab-a644-d62a1d549b65', 'fda3455c-6514-4043-afee-f1a5a9eed220'  -- Søfartsstyrelsen
    UNION ALL SELECT 'a1d46062-7b54-415d-a4d6-6d5118bdff90', 'db1d810e-5c58-4dc3-b385-119f4a98b01c'  -- Thomas Cook Airlines Scandinavia
    UNION ALL SELECT '904f0d0c-2f3b-4cb1-a613-ca85fc6ccd96', '65fb4bb2-0ad5-4f28-8d43-910bc9ab6f4b'  -- Topdanmark
    UNION ALL SELECT '9f2304e3-d2f1-48b8-9e10-54f6887b1438', '40c93307-1dfa-405a-8211-37cbda75318b'  -- TRUSTWORKS A/S
    UNION ALL SELECT '44fb817f-f611-4e87-8052-3fae3960525c', '228d10aa-f959-4662-9f30-1f7777bf7caa'  -- Trustworks Academy
    UNION ALL SELECT '752fe6a6-1ac3-459f-b59e-bfdcb031bd49', '36051376-b3d1-4fc4-b7fe-2f1f915fdebb'  -- Tryg
    UNION ALL SELECT 'a11c84a8-5c82-4a59-948b-175b11818576', '99636db6-b2cd-4f3b-863c-6d578f922563'  -- TV2
    UNION ALL SELECT '11edd3d4-962c-4eae-9bf8-fcd744c795c5', '6d2014d2-53da-4d3d-82c0-9637b6f71c08'  -- UC Lillebælt
    UNION ALL SELECT '6213c47f-79b8-4ef7-b632-20406991c4ac', '1046704f-565e-42c8-95dd-9effe53724c4'  -- Udenrigsministeriet
    UNION ALL SELECT '68153561-a32b-49dc-b2ae-e75c1e868621', '7636b3e5-c250-4b6c-8902-4cce8b82d51a'  -- Udviklings- og Forenklingsstyrelsen
    UNION ALL SELECT '06255580-2aea-4a11-b593-7437563d7fb8', 'ea5d5bec-95fc-440b-bd4a-5f6b60333e83'  -- UXQB
    UNION ALL SELECT '790bbbb0-6dc4-4ba5-964e-7095cfd030b9', '2cbb7f5e-9e2b-4edc-870b-e9591dc58891'  -- VATTENFALL VINDKRAFT A/S
    UNION ALL SELECT '2a297857-b551-4264-9a25-97b44df72093', 'e0470ad5-d372-4c4c-be65-1eeae752c973'  -- World Economic Forum
    UNION ALL SELECT '22cd9be2-f8ee-4703-9e59-4174740be355', '2a44473b-d20d-4c0b-81ce-62a4d1368497'  -- Ørsted
    -- ---- user (88) ----
    UNION ALL SELECT 'd6a40b2d-3784-4a99-bbc0-690d107411bd', '14e87650-9fbc-4962-ab70-7fa0e7eefdfd'  -- Amalie  Obel
    UNION ALL SELECT '6915f36b-4e2d-4ca1-8dbf-de95f58bd77a', '19c6d923-d5b0-4505-a450-34848da13861'  -- Anette Nørbjerg Luffe
    UNION ALL SELECT 'df8308f9-2419-4023-b7f6-088f1c9323fa', '47d71d0c-0ce3-4c67-a20f-0b162815bf38'  -- Ania Rybicka
    UNION ALL SELECT '18bfa12a-bc5c-4166-9c03-ed3e20e2d623', '657919ae-3bd6-4954-8909-6f4c9bc565ca'  -- Anna Rønne Møller Vang
    UNION ALL SELECT '6d837623-4b1d-4dbc-8bac-e3b1dcc8c704', 'b7580254-fdb7-40f8-860e-9d8c7e4eab6f'  -- Anne Cathrine Andresen
    UNION ALL SELECT '747c6350-3f6d-4fa8-ad38-3b893591e49e', 'cc3697a0-581b-11e6-8b77-86f30ca893d3'  -- Anne Walther
    UNION ALL SELECT 'c7dc94c2-d751-4aea-a9b4-e42097252c62', '6347f130-33e4-11e7-a919-92ebcb67fe33'  -- Arni Einarsson
    UNION ALL SELECT 'd4a7e367-c389-4b9a-b635-4fb1c52a937d', 'e32fdbc2-ac8d-4a4c-b0b6-c096090ebf4c'  -- Brian Flaskager
    UNION ALL SELECT '2b1f57c4-c8cb-4972-acf9-5b8c13f827d2', 'f2929914-f777-40a4-ba66-5ab9ab4fedd4'  -- Casper Adrian König
    UNION ALL SELECT 'e430f74f-f03b-4423-9658-2e954eb3d9b2', '1b9299b0-68e2-40c2-858a-625a381cece6'  -- Casper Mørup Mellergaard
    UNION ALL SELECT '3347077d-7c53-474e-b89d-c936cfea475d', 'ee2315ff-97e0-11e4-a1f7-07091a64aa27'  -- Christian Rønn Jensen
    UNION ALL SELECT '58510827-44a2-448b-aa93-cc93103d0072', '9750fd10-d6f2-4e18-ac58-c5924ceb279a'  -- Christiane Wasehus Ramstrup Rudolph
    UNION ALL SELECT 'eff85c92-a689-4b83-84cb-acc2af185e2d', '1f04abcf-4bfc-4e4b-a625-907c03d927f8'  -- Christine Islev Noes
    UNION ALL SELECT '5622620a-684f-4700-963f-532670aa039a', 'cc3fa234-12fe-42ee-b44c-817ecacd8fea'  -- Daniel Østvand
    UNION ALL SELECT 'cb2a50e6-43fc-44d0-a481-65fa97381690', 'd7a1061b-8de5-44f6-8bc2-ee52ed32a149'  -- Ditte Marie Hjorth
    UNION ALL SELECT '6938b45e-8ae1-41e7-96bd-d1b87528e7bc', '23e394fb-2b7b-4fc6-9874-54d894f1e5e0'  -- Emil Hauberg
    UNION ALL SELECT '498116f4-af08-4fd6-8c63-dd5a6e769feb', '85d511d6-e064-4422-a53d-e1c5c594cdc1'  -- Emil Tophøj
    UNION ALL SELECT '8382d87a-9f4e-4621-b125-0aa238fe2d28', '035b4af1-7b43-4a26-81c6-76ecf49cadae'  -- Emilie Duedahl
    UNION ALL SELECT '6af8be6e-00c2-4558-990f-5aee16c2b309', 'f6d5d819-ff07-417b-921f-4d77986d8c4a'  -- Emilie Horneman
    UNION ALL SELECT '8ee568bb-9ef7-4dac-a276-fc00d565bb45', '8caba3a2-5a3f-4caa-a345-bac187220338'  -- Emilie Kent
    UNION ALL SELECT 'f4b604be-f967-4e8b-8cf3-a7ff9d89e3f1', '2fed47e0-5ff5-11e6-8b77-86f30ca893d3'  -- Gisla Faber
    UNION ALL SELECT '120b4b85-59b7-4258-aa92-adffea604ae6', '48f88806-7ebc-471b-99c5-82bc2142fb09'  -- Helene Damsholt Gadegaard
    UNION ALL SELECT 'c7c5f952-4cdf-4b43-bf62-59f14bf90773', 'd7a43f48-6a94-4675-8a22-77a08d08adfb'  -- Henrik Hvid Jensen
    UNION ALL SELECT '59f0cc50-fa8b-4a16-b279-b24bbef0a7fb', 'fd5253dd-f237-422c-93b1-1fb8fd6c4e44'  -- Henrik Kjems
    UNION ALL SELECT '155dcd82-49e6-4c9d-a72c-d20a0616fd11', '71421343-1129-445a-bd8a-1fe02ff4a768'  -- Ida Hupfeld
    UNION ALL SELECT '19ae1672-82b3-408b-8d7b-60d46397838b', '01e2dd1d-ae16-4c67-885f-abe5651fdaff'  -- Jacob Breindahl
    UNION ALL SELECT 'ecd98463-b26a-4cce-8a0e-8e993eb61cf4', '8c2c52b1-f39a-40fe-bed4-9c311ed57697'  -- Jan Borg
    UNION ALL SELECT '34999a0c-11a7-4507-a674-0b2d21e6b741', '18a71ae8-e952-42fe-9ca7-069c5138a923'  -- Jane Maigaard
    UNION ALL SELECT '6d2341cb-271e-40b4-bcc9-0d601d624ff1', 'e6bdc3ad-1c4c-4ed2-a4ab-12d468e1fb55'  -- Janni Thoft
    UNION ALL SELECT '389443de-78fb-4a27-9f65-72518a8ad345', '6166e09c-5ff5-11e6-8b77-86f30ca893d3'  -- Jeanette Hansen
    UNION ALL SELECT 'b1d40da9-ddd8-4d4b-9f4e-c3744d533b4d', '2dc5f35d-cef6-40b4-9f67-f815d3059be8'  -- Jennifer Ravn
    UNION ALL SELECT '58692d9d-91e8-4f0e-acc9-ba49809834b3', '30e31001-b770-4911-9484-f5d14c01fa69'  -- Jens Østervang Jensen
    UNION ALL SELECT 'd7ee3a61-5700-4847-821b-cfc71e651019', '5b326b98-122d-4bec-895c-67e23144b2f6'  -- Jonas Østergaard Fisker
    UNION ALL SELECT '2040361d-d86b-4827-834f-1759f29b77bb', '901d4a25-5749-47b5-a497-c5af994bf35d'  -- Jonathan  Frystyk
    UNION ALL SELECT 'aa7e9c80-c6a0-471b-9cbd-8431c3fde1c1', 'aedab4c3-d7c8-4379-998b-1593484db7af'  -- Kasper Kronborg
    UNION ALL SELECT '99de8c86-28a2-43cc-9dc7-3799d6278fa4', 'aa53fac3-ce74-4068-a807-71798d9be757'  -- Konrad Kubak
    UNION ALL SELECT '24465d88-eca7-48a4-9d78-a7304c629cf6', 'bc2f6cba-1a66-4604-8dbe-fedfef8d31fb'  -- Kurt Hansen
    UNION ALL SELECT 'b691342c-2b27-4a60-944f-25db8aa512ea', '0571e4c6-5ff5-11e6-8b77-86f30ca893d3'  -- Lars Albert
    UNION ALL SELECT 'ee138d30-0756-43db-803d-0ef011d1946c', 'c69e2c6d-0d24-45a8-9550-4e5032fe17e7'  -- Lars Michael Brink
    UNION ALL SELECT '3dd3e542-5437-4e10-a71b-5c45e26c68cd', 'ba8fbcc1-e061-45bf-b733-59f43b736b4d'  -- Letty Meszaros
    UNION ALL SELECT '3b791e37-eafb-4c7a-a001-cad4ef9a08c9', '2887bca3-6dff-4deb-b4fd-4a7719314571'  -- Linda Faurskov
    UNION ALL SELECT 'a83d3852-ebe3-444d-a3cc-58ea8a6cedf2', '1e948a87-1705-4a92-931f-34542c5f7e69'  -- Mads Christiansen
    UNION ALL SELECT '8957ee70-561e-46d7-ad44-f95dfd966c26', '2d2dea17-8773-400c-9db5-e22b4d766f98'  -- Marie Daugaard
    UNION ALL SELECT '2c13642c-30ca-4423-9b4d-bd39fcfded22', 'fcfe998f-b38a-421a-899b-2d8dd32029ee'  -- Marie Dorthea Sørensen
    UNION ALL SELECT 'fab05fb2-286a-4277-981b-57d4427556a2', 'd2878586-800e-4ba2-ac3c-93e5ac69834c'  -- Martin Mouroux
    UNION ALL SELECT '2bbf0381-5cb7-4847-b9b5-70ad90e0e7a9', '29452d70-b04d-4f81-b4ba-d1500877cc39'  -- Mathias Herbst-Jensen
    UNION ALL SELECT '124be6b0-bada-4c94-9e21-b39d379d475f', 'b7e20286-9c5c-420e-9290-bfcb019c2e2b'  -- Mathias Rasmussen
    UNION ALL SELECT '06d4c1b0-9db1-4230-a4f0-4c63b2ba8953', '9775d8e8-1413-4a2f-9a9b-ec544e1216e0'  -- Mathilde Lange
    UNION ALL SELECT '98805fe3-5248-447a-9319-ab84da660cf4', '2f2c2e46-ef69-4f68-8b6e-2f5624facc72'  -- Mette Buhl Christoffersen
    UNION ALL SELECT 'fafd45bb-b35a-4fc7-b1b0-7017b2ad1474', '3dce26ec-ec52-11e6-b006-92361f002671'  -- Michael Bruun Ellegaard
    UNION ALL SELECT '88fe0292-646b-4d25-98be-a60a2f694448', '846c304b-13b8-4339-9fca-9a9a7ff07a80'  -- Michael Redlich
    UNION ALL SELECT '6bb7c24b-9d69-43b9-b65b-8131dfc05a88', 'a80e6c66-eced-11e5-9ce9-5e5517507c66'  -- Michala Christensen
    UNION ALL SELECT 'e4e852c3-1669-4da8-add1-e0f1d4e29f65', 'f6a36952-c2e8-4e9a-b9ec-1deaa2b2ccfe'  -- Mikkel Frederiksen
    UNION ALL SELECT '44d0cd10-ae5a-4e5f-9a23-048f955ecc66', '7c4dd57a-2f23-11e7-93ae-92361f002671'  -- Mimi Musgrove
    UNION ALL SELECT 'e4198f6e-ab7c-4a43-b63f-71e9a51f6f33', 'f41b4264-587d-4390-899c-3dec1bf07fe0'  -- Morten Press
    UNION ALL SELECT 'bab49c17-fdb9-4fb1-bda2-f56a26822fe7', '99c3b6b6-5f42-4571-9942-02b9b46985fe'  -- Naja Villien
    UNION ALL SELECT '6edbd9e2-b8b6-4406-a9d7-0e6699c825b9', '2c1829c3-f9ec-4e93-a6cc-20dbd7bd3f64'  -- Natascha Pedersen
    UNION ALL SELECT '2197d747-5479-43fe-8dcc-7137567997dc', '8e15d9c8-1df5-4c32-8e98-001cfabc54b8'  -- Nicklas Grunnet Sandager
    UNION ALL SELECT '5889e1cf-f2cb-41c1-bf19-2e9181456cb5', '70b19352-b7db-4a55-abb7-a84259cf9d64'  -- Nicole Agger-Nielsen
    UNION ALL SELECT 'd59e887c-8877-482d-9050-8eeeefefcf74', 'b2c6e16a-2f5e-4380-bce6-ea2b4b4f4669'  -- Niels Berg
    UNION ALL SELECT '445f6cbd-ecdc-4e65-bb99-72b745e8f374', '8a726fd6-4ee4-11e7-b114-b2f933d5fe66'  -- Nikolai Hart
    UNION ALL SELECT 'c5f742b8-4eaa-4596-8e9e-e514201e261c', 'de9f35c0-dc9d-43a4-b3a4-570e8df3e568'  -- Nikolaj Birch
    UNION ALL SELECT 'f0ed1dc0-535e-4411-8654-4f126e084892', '1fe20cbc-dce1-4f3b-82a1-a9a0d752662d'  -- Oliver Skjønnemand
    UNION ALL SELECT '52751deb-b65b-49f1-a751-f045492811db', 'ee232edf-97e0-11e4-a1f7-07091a64aa27'  -- Paula Høiby
    UNION ALL SELECT '53222f41-5a00-4bf6-ba97-30b78afbee1b', 'ade4859d-9c2f-4071-a492-d6fb8bf421ad'  -- Peter Gaarde
    UNION ALL SELECT '8774ef1c-f694-4915-8674-b491b4c907b5', '276fe052-a892-4f89-8390-ebbbec10b7ea'  -- Peter Lester
    UNION ALL SELECT '971b1a46-a6e8-4030-b519-e385b0db4bca', 'd52ddb22-68e5-456f-8829-28b1f89cc346'  -- Rasmus Frandsen
    UNION ALL SELECT '4d65e904-6595-4bf1-aeb6-6f3beedb6c3c', '84cb1cba-353d-4d45-bfc5-51c08a21bb64'  -- Rasmus Saltoft Steen
    UNION ALL SELECT '03b589d6-3af1-470e-b57f-e6875559f4a8', 'b3544e89-e1ae-4b8e-b964-d795518b9663'  -- Regitze da Costa Carneiro
    UNION ALL SELECT '5623db23-f97e-46e2-a594-60b854543dff', '4d76267e-2208-4a81-940a-7ad126329a0c'  -- René Løjmand
    UNION ALL SELECT 'f274fd1c-f41b-40ed-95fe-030bb4588e84', '980573fe-8938-11e6-ae22-56b6b6499611'  -- Rikke Kallerup
    UNION ALL SELECT '441db771-b859-4e48-8612-f87a9f531d0b', '310c299e-5edc-4329-baa7-782e33f18ddf'  -- Sara  Barzan
    UNION ALL SELECT '88a45a22-fd97-429c-a9ee-238ee8425421', 'f98115bd-ed78-486d-ae3c-ce1acd7ba969'  -- Sarah Rasmussen
    UNION ALL SELECT 'd3818e5e-625c-419d-b32c-236203ecc7e4', 'ec152c5b-6b9b-49be-ac84-27a0ffc1c0b2'  -- Signe Stoholm Sørensen
    UNION ALL SELECT 'a5020928-3130-4d4f-b623-c5a7d1ea571c', '64035502-bcfc-11e5-9912-ba0be0483c18'  -- Simon Gomez
    UNION ALL SELECT '1435568d-2147-435b-8419-07048eca7a96', '35f38aa8-e851-11e5-9ce9-5e5517507c66'  -- Simon Warthoe
    UNION ALL SELECT '20140f44-0eff-4450-8d9e-d20dfe540850', 'dea13214-18e8-11e6-b6ba-3e1d05defe78'  -- Sofie Boye
    UNION ALL SELECT '4e2f1d5a-af2e-4adb-80c7-e0a355c08f74', '46e1816c-8131-4061-969e-0822c75c7a23'  -- Sofie Damkjær Østensgård (row 4325192c deleted in step 1)
    UNION ALL SELECT '25031cdc-9f21-42dc-a7c5-f2c8ba90cdfa', '64e41421-9c41-4b11-ae03-45a27b524cd5'  -- Sophie Louise Andersen
    UNION ALL SELECT 'a7572bb5-d5ee-4d37-be96-c5a951acd525', 'b48f06f2-f96f-11e9-8f0b-362b9e155667'  -- Stefan Vesløv
    UNION ALL SELECT '64103fa8-59e8-40e4-988a-f3f8d034709b', 'f6626f6c-e7c8-11e6-bf01-fe55135034f3'  -- Stephan Jensen
    UNION ALL SELECT '9e5cfacb-898e-41e6-9d62-b65d3402d8f4', '1796d48d-1fa4-4238-bbc8-cc4f4ec058fb'  -- Stinne  Riis
    UNION ALL SELECT '09bcec4f-c32a-40c2-8b2b-5701507336e7', '8fa7f75a-57bf-4c6f-8db7-7e16067c1bcd'  -- Thomas Gammelvind
    UNION ALL SELECT 'be6d13b8-b8f5-489a-99e5-02e6a4c65217', 'c4eef92d-c928-424f-8893-31abe7fbb7a5'  -- Thomas Løber
    UNION ALL SELECT '5a59c3db-0392-4a8b-b54c-83ebbf6eb4dd', '4d952415-94e5-11e4-a1f7-07091a64aa27'  -- Tina Karlsen
    UNION ALL SELECT '307eeab7-9c7e-4fdc-8c8f-47bbf94fa404', 'ca0e1027-061f-49e7-b66a-a487c815f5a0'  -- Tobias Kjølsen
    UNION ALL SELECT 'eb1a7807-92cd-4882-8f5f-01d32b96992e', 'd25e26a8-fdf2-11e4-b1b0-49f6f6edc206'  -- Tommy Sørensen
    UNION ALL SELECT 'cdfb115c-b889-40ea-b702-37858d5cde9c', '9b5cec25-608d-4e9a-acb3-3906bd72b728'  -- Vithujaan Kannathasan
    -- ---- course (17) ----
    UNION ALL SELECT 'db96d6db-c721-4143-96bd-107c1f96ca9b', 'e98fa5ac-4fe7-49f6-82cd-8c144c4b93cc'  -- 
    UNION ALL SELECT '7e098c06-8f18-426c-93c0-bfe8484acb50', 'c9da0520-e04a-46fd-b6c0-ed0869d3e6d5'  -- 
    UNION ALL SELECT '69089623-206d-4a06-bc5b-e26514794435', '537b688d-3674-4c7f-a57b-a92a130d9a55'  -- 
    UNION ALL SELECT '7ca27e74-5ef8-49f9-82c6-51e28449b5d4', '20fabac5-50e5-45e0-8637-6e79faf42875'  -- 
    UNION ALL SELECT '8a2226d9-5ab5-49a3-9564-765ba760828f', '49364337-8262-48b9-81a8-d7878414cda0'  -- 
    UNION ALL SELECT '3d3227fa-b89a-4d4f-8140-7ec5f459ead0', '0bdde6be-cd9b-4804-816c-3bc7aa7849bd'  -- 
    UNION ALL SELECT '4d5fcc47-7d8a-4a73-b526-d849c1aec149', 'cd92131e-54df-44b6-89a5-c5dbce7c7046'  -- 
    UNION ALL SELECT '736db7c1-48fa-4635-a007-8dc2490ec8fe', '391aa43f-e262-437d-b302-aaea2011d1f6'  -- 
    UNION ALL SELECT '36d70591-0ed7-4ba5-b2c5-c08f7374c9a4', '92d11af5-09a6-46a1-ac17-fbdc827c2825'  -- 
    UNION ALL SELECT '9ae1f0d5-beed-4fa2-a454-d94cfc1be97f', '9963f26a-175c-461a-952f-3774e6c6bb5e'  -- 
    UNION ALL SELECT '2ebbbf9c-7da1-4e0f-910e-ac7b40bd13d5', '2bbcfd03-5269-4d88-a754-04c6ccbdea2b'  -- 
    UNION ALL SELECT 'bd24887b-a5eb-4c0c-ac8d-46fc43440d8f', 'c75ec400-37d4-4825-8ad2-f58944b71342'  -- 
    UNION ALL SELECT '6f039a3a-88fb-4296-a01b-ebd9d41308ae', '7917be17-d36a-4fe7-a085-48493a5b5aaf'  -- 
    UNION ALL SELECT '8db17e3a-084d-444f-b73d-17fdfff9ac43', '6e64fb1f-7f86-4b8f-bc9b-c688b4d3d395'  -- 
    UNION ALL SELECT '33abc102-2c17-4ceb-8e93-a067f477481e', '0dbcc346-405a-4347-a716-f2dc02a9123f'  -- 
    UNION ALL SELECT 'b9ef592d-51d0-4546-b958-8ab56e6e0ed6', '1f8d2f4c-dff3-4da1-8668-f4a65b25ab59'  -- 
    UNION ALL SELECT '62215045-82bc-46b6-83eb-957360057cf7', '06e4fc3c-78d0-46e8-9395-1520aeae24c6'  -- 
) AS c
WHERE NOT EXISTS (
    SELECT 1 FROM files f WHERE f.relateduuid = c.relateduuid AND f.type = 'PHOTO'
);
SELECT ROW_COUNT() AS rows_restored;  -- expect 193

-- Step 3 — checks before committing.
-- No relateduuid may hold two PHOTO rows (reader has no ORDER BY): expect empty.
SELECT relateduuid, COUNT(*) AS n
FROM files WHERE type = 'PHOTO'
GROUP BY relateduuid HAVING n > 1;

-- Coverage. Expected: user 217/240, client 238/290, team 14/15
-- (HiTech is the one team without a logo — it never had one).
SELECT 'user' AS entity, COUNT(*) AS total,
       SUM(EXISTS(SELECT 1 FROM files f WHERE f.relateduuid = e.uuid AND f.type = 'PHOTO')) AS with_photo
FROM user e
UNION ALL
SELECT 'client', COUNT(*),
       SUM(EXISTS(SELECT 1 FROM files f WHERE f.relateduuid = e.uuid AND f.type = 'PHOTO'))
FROM client e
UNION ALL
SELECT 'team', COUNT(*),
       SUM(EXISTS(SELECT 1 FROM files f WHERE f.relateduuid = e.uuid AND f.type = 'PHOTO'))
FROM team e;

-- COMMIT;   -- uncomment once both checks look right
-- ROLLBACK;

-- Post-restore verification (after backend restart):
--   GET /files/photos/{relateduuid}/jpg returns non-empty image/jpeg for e.g.
--   team 2fea1fd5-a9f1-4262-817e-908e6f20ca22 (Team it), and /organization
--   renders every team card except HiTech with a logo.
