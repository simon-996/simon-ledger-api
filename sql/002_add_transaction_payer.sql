ALTER TABLE ledger_transaction
    ADD COLUMN payer_person_id BIGINT NULL COMMENT 'expense payer person id, null means shared pool' AFTER type,
    ADD KEY idx_ledger_transaction_payer_person_id (payer_person_id),
    ADD CONSTRAINT fk_ledger_transaction_payer_person_id FOREIGN KEY (payer_person_id) REFERENCES ledger_person (id);
