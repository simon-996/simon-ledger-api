USE simon_ledger;

ALTER TABLE user_account ADD COLUMN version INT NOT NULL DEFAULT 1 AFTER status;
ALTER TABLE ledger ADD COLUMN version INT NOT NULL DEFAULT 1 AFTER owner_user_id;
ALTER TABLE ledger_member ADD COLUMN version INT NOT NULL DEFAULT 1 AFTER status;
ALTER TABLE ledger_person ADD COLUMN version INT NOT NULL DEFAULT 1 AFTER avatar;
