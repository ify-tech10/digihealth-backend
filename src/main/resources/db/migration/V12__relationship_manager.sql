-- V12: facilities onboarded by a Relationship Manager.
-- The RM may not have the facility admin's email yet, so there may be no login until Admin adds one.
ALTER TABLE facilities ALTER COLUMN email          DROP NOT NULL;
ALTER TABLE facilities ALTER COLUMN contact_person DROP NOT NULL;

-- Everything else the RM records (type, bed capacity, services, accreditation...) as JSON text.
ALTER TABLE facilities ADD COLUMN details TEXT;
