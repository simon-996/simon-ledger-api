USE simon_ledger;

-- Fresh 001 and upgraded databases both apply this migration exactly once.
-- Original transaction currencies and amounts are preserved.
ALTER TABLE ledger ADD COLUMN currency_rates_to_cny JSON NULL AFTER exchange_rate_to_cny;
-- Older APIs allowed a non-unit legacy alias even when the base currency was CNY.
UPDATE ledger SET exchange_rate_to_cny = 1 WHERE UPPER(TRIM(base_currency_code)) = 'CNY';
UPDATE ledger
SET currency_rates_to_cny = CASE
    WHEN UPPER(TRIM(base_currency_code)) = 'CNY' THEN JSON_OBJECT('CNY', 1)
    WHEN TRIM(base_currency_code) REGEXP '^[A-Za-z][A-Za-z0-9]{2,15}$' AND exchange_rate_to_cny > 0
        THEN JSON_OBJECT('CNY', 1, UPPER(TRIM(base_currency_code)), CAST(exchange_rate_to_cny AS CHAR))
    ELSE JSON_OBJECT('CNY', 1)
END;

-- All avatar writes and API output use empty string for the default avatar.
UPDATE user_account SET avatar = '' WHERE avatar IS NULL;
ALTER TABLE user_account MODIFY avatar VARCHAR(255) NOT NULL DEFAULT '';
ALTER TABLE ledger_person MODIFY avatar VARCHAR(255) NOT NULL DEFAULT '';

-- Retain before/after authorization evidence without copying personal account data.
ALTER TABLE admin_operation_log
    ADD COLUMN previous_value BOOLEAN NULL AFTER detail,
    ADD COLUMN new_value BOOLEAN NULL AFTER previous_value;

-- Store the exact serialized JSON instead of MySQL's binary JSON numeric conversion.
-- This also preserves large DECIMAL(18,2) amounts and all 8 rate decimal places on replay.
ALTER TABLE idempotency_record MODIFY response_body MEDIUMTEXT NULL;
-- A legacy committed processing row has an unknown business outcome: reserve its key.
-- Run migrations while the old API is stopped so no legacy completion can race this.
UPDATE idempotency_record SET response_code = -2, response_body = NULL WHERE response_code = -1;
