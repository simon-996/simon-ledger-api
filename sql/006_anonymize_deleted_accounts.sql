USE simon_ledger;

ALTER TABLE ledger_transaction
    DROP FOREIGN KEY fk_ledger_transaction_created_by_user_id;
ALTER TABLE ledger_transaction
    MODIFY COLUMN created_by_user_id BIGINT NULL;
ALTER TABLE ledger_transaction
    ADD CONSTRAINT fk_ledger_transaction_created_by_user_id
        FOREIGN KEY (created_by_user_id) REFERENCES user_account (id);

ALTER TABLE ledger_change_log
    DROP FOREIGN KEY fk_ledger_change_log_operator_user_id;
ALTER TABLE ledger_change_log
    MODIFY COLUMN operator_user_id BIGINT NULL;
ALTER TABLE ledger_change_log
    ADD CONSTRAINT fk_ledger_change_log_operator_user_id
        FOREIGN KEY (operator_user_id) REFERENCES user_account (id);

-- A committed account deletion leaves a short-lived outbox item until all old sessions are revoked.
CREATE TABLE IF NOT EXISTS account_session_revocation_queue
(
    login_id        BIGINT PRIMARY KEY,
    attempts        INT      NOT NULL DEFAULT 0,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_attempt_at DATETIME NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
