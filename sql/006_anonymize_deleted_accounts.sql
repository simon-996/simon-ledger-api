USE simon_ledger;

ALTER TABLE ledger_transaction
    DROP FOREIGN KEY fk_ledger_transaction_created_by_user_id,
    MODIFY COLUMN created_by_user_id BIGINT NULL,
    ADD CONSTRAINT fk_ledger_transaction_created_by_user_id
        FOREIGN KEY (created_by_user_id) REFERENCES user_account (id);

ALTER TABLE ledger_change_log
    DROP FOREIGN KEY fk_ledger_change_log_operator_user_id,
    MODIFY COLUMN operator_user_id BIGINT NULL,
    ADD CONSTRAINT fk_ledger_change_log_operator_user_id
        FOREIGN KEY (operator_user_id) REFERENCES user_account (id);
