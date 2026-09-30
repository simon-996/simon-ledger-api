USE simon_ledger;

ALTER TABLE user_account ADD COLUMN ai_bookkeeping_enabled TINYINT(1) NOT NULL DEFAULT 0;
