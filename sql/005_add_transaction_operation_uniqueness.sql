USE simon_ledger;

ALTER TABLE ledger_transaction
    ADD COLUMN active_operation_slot TINYINT GENERATED ALWAYS AS
        (CASE WHEN deleted_at IS NULL THEN 1 ELSE NULL END) STORED,
    ADD UNIQUE KEY uk_ledger_transaction_active_operation
        (ledger_id, created_by_user_id, client_operation_id, active_operation_slot);
