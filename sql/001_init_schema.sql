CREATE DATABASE IF NOT EXISTS simon_ledger
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE simon_ledger;

CREATE TABLE IF NOT EXISTS user_account
(
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    uuid          VARCHAR(64)  NOT NULL,
    email         VARCHAR(128) NULL,
    phone         VARCHAR(32)  NULL,
    password_hash VARCHAR(255) NULL,
    nickname      VARCHAR(64)  NOT NULL,
    avatar        VARCHAR(255) NULL,
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1 normal, 2 disabled',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at    DATETIME     NULL,
    UNIQUE KEY uk_user_account_uuid (uuid),
    UNIQUE KEY uk_user_account_email (email),
    UNIQUE KEY uk_user_account_phone (phone),
    KEY idx_user_account_status (status),
    KEY idx_user_account_deleted_at (deleted_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS ledger
(
    id                   BIGINT PRIMARY KEY AUTO_INCREMENT,
    uuid                 VARCHAR(64)    NOT NULL,
    name                 VARCHAR(128)   NOT NULL,
    base_currency_code   VARCHAR(16)    NOT NULL,
    exchange_rate_to_cny DECIMAL(18, 8) NOT NULL DEFAULT 1.00000000,
    owner_user_id        BIGINT         NOT NULL,
    created_at           DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at           DATETIME       NULL,
    UNIQUE KEY uk_ledger_uuid (uuid),
    KEY idx_ledger_owner_user_id (owner_user_id),
    KEY idx_ledger_deleted_at (deleted_at),
    CONSTRAINT fk_ledger_owner_user_id FOREIGN KEY (owner_user_id) REFERENCES user_account (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS ledger_member
(
    id         BIGINT PRIMARY KEY AUTO_INCREMENT,
    uuid       VARCHAR(64) NOT NULL,
    ledger_id  BIGINT      NOT NULL,
    user_id    BIGINT      NOT NULL,
    role       VARCHAR(32) NOT NULL COMMENT 'owner, admin, editor, viewer',
    status     TINYINT     NOT NULL DEFAULT 1 COMMENT '1 active, 2 disabled',
    joined_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME    NULL,
    UNIQUE KEY uk_ledger_member_uuid (uuid),
    UNIQUE KEY uk_ledger_member_ledger_user (ledger_id, user_id),
    KEY idx_ledger_member_user_id (user_id),
    KEY idx_ledger_member_role (role),
    KEY idx_ledger_member_status (status),
    KEY idx_ledger_member_deleted_at (deleted_at),
    CONSTRAINT fk_ledger_member_ledger_id FOREIGN KEY (ledger_id) REFERENCES ledger (id),
    CONSTRAINT fk_ledger_member_user_id FOREIGN KEY (user_id) REFERENCES user_account (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS ledger_person
(
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    uuid           VARCHAR(64) NOT NULL,
    ledger_id      BIGINT      NOT NULL,
    linked_user_id BIGINT      NULL,
    name           VARCHAR(64) NOT NULL,
    avatar         VARCHAR(64) NOT NULL DEFAULT '',
    created_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at     DATETIME    NULL,
    UNIQUE KEY uk_ledger_person_uuid (uuid),
    KEY idx_ledger_person_ledger_id (ledger_id),
    KEY idx_ledger_person_linked_user_id (linked_user_id),
    KEY idx_ledger_person_deleted_at (deleted_at),
    CONSTRAINT fk_ledger_person_ledger_id FOREIGN KEY (ledger_id) REFERENCES ledger (id),
    CONSTRAINT fk_ledger_person_linked_user_id FOREIGN KEY (linked_user_id) REFERENCES user_account (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS ledger_transaction
(
    id                       BIGINT PRIMARY KEY AUTO_INCREMENT,
    uuid                     VARCHAR(64)    NOT NULL,
    ledger_id                BIGINT         NOT NULL,
    type                     TINYINT        NOT NULL COMMENT '0 expense, 1 income',
    payer_person_id          BIGINT         NULL COMMENT 'expense payer person id, null means shared pool',
    amount                   DECIMAL(18, 2) NOT NULL,
    currency_code            VARCHAR(16)    NOT NULL,
    category                 VARCHAR(64)    NOT NULL,
    note                     VARCHAR(512)   NULL,
    created_by_user_id       BIGINT         NOT NULL,
    last_modified_by_user_id BIGINT         NULL,
    client_operation_id      VARCHAR(128)   NULL,
    version                  INT            NOT NULL DEFAULT 1,
    happened_at              DATETIME       NOT NULL,
    created_at               DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at               DATETIME       NULL,
    UNIQUE KEY uk_ledger_transaction_uuid (uuid),
    KEY idx_ledger_transaction_ledger_happened_at (ledger_id, happened_at),
    KEY idx_ledger_transaction_ledger_type (ledger_id, type),
    KEY idx_ledger_transaction_payer_person_id (payer_person_id),
    KEY idx_ledger_transaction_ledger_category (ledger_id, category),
    KEY idx_ledger_transaction_created_by_user_id (created_by_user_id),
    KEY idx_ledger_transaction_last_modified_by_user_id (last_modified_by_user_id),
    KEY idx_ledger_transaction_client_operation_id (client_operation_id),
    KEY idx_ledger_transaction_deleted_at (deleted_at),
    CONSTRAINT fk_ledger_transaction_ledger_id FOREIGN KEY (ledger_id) REFERENCES ledger (id),
    CONSTRAINT fk_ledger_transaction_payer_person_id FOREIGN KEY (payer_person_id) REFERENCES ledger_person (id),
    CONSTRAINT fk_ledger_transaction_created_by_user_id FOREIGN KEY (created_by_user_id) REFERENCES user_account (id),
    CONSTRAINT fk_ledger_transaction_last_modified_by_user_id FOREIGN KEY (last_modified_by_user_id) REFERENCES user_account (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS ledger_transaction_person
(
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    transaction_id BIGINT         NOT NULL,
    person_id      BIGINT         NOT NULL,
    share_amount   DECIMAL(18, 2) NULL,
    share_ratio    DECIMAL(10, 6) NULL,
    created_at     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_transaction_person (transaction_id, person_id),
    KEY idx_ledger_transaction_person_person_id (person_id),
    CONSTRAINT fk_ledger_transaction_person_transaction_id FOREIGN KEY (transaction_id) REFERENCES ledger_transaction (id),
    CONSTRAINT fk_ledger_transaction_person_person_id FOREIGN KEY (person_id) REFERENCES ledger_person (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS ledger_invite
(
    id                 BIGINT PRIMARY KEY AUTO_INCREMENT,
    uuid               VARCHAR(64) NOT NULL,
    ledger_id          BIGINT      NOT NULL,
    code               VARCHAR(64) NOT NULL,
    role               VARCHAR(32) NOT NULL COMMENT 'admin, editor, viewer',
    created_by_user_id BIGINT      NOT NULL,
    max_uses           INT         NULL,
    used_count         INT         NOT NULL DEFAULT 0,
    expires_at         DATETIME    NOT NULL,
    created_at         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    disabled_at        DATETIME    NULL,
    UNIQUE KEY uk_ledger_invite_uuid (uuid),
    UNIQUE KEY uk_ledger_invite_code (code),
    KEY idx_ledger_invite_ledger_id (ledger_id),
    KEY idx_ledger_invite_created_by_user_id (created_by_user_id),
    KEY idx_ledger_invite_expires_at (expires_at),
    CONSTRAINT fk_ledger_invite_ledger_id FOREIGN KEY (ledger_id) REFERENCES ledger (id),
    CONSTRAINT fk_ledger_invite_created_by_user_id FOREIGN KEY (created_by_user_id) REFERENCES user_account (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS idempotency_record
(
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id        BIGINT       NOT NULL,
    request_key    VARCHAR(128) NOT NULL,
    request_method VARCHAR(16)  NOT NULL,
    request_path   VARCHAR(255) NOT NULL,
    response_code  INT          NOT NULL,
    response_body  JSON         NULL,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at     DATETIME     NOT NULL,
    UNIQUE KEY uk_idempotency_user_request_key (user_id, request_key),
    KEY idx_idempotency_expires_at (expires_at),
    KEY idx_idempotency_request_path (request_path),
    CONSTRAINT fk_idempotency_user_id FOREIGN KEY (user_id) REFERENCES user_account (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS ledger_change_log
(
    id               BIGINT PRIMARY KEY AUTO_INCREMENT,
    uuid             VARCHAR(64) NOT NULL,
    ledger_id        BIGINT      NOT NULL,
    entity_type      VARCHAR(32) NOT NULL COMMENT 'ledger, member, person, transaction',
    entity_uuid      VARCHAR(64) NOT NULL,
    operation        VARCHAR(32) NOT NULL COMMENT 'create, update, delete',
    operator_user_id BIGINT      NOT NULL,
    version          INT         NOT NULL,
    created_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ledger_change_log_uuid (uuid),
    UNIQUE KEY uk_ledger_change_log_ledger_version (ledger_id, version),
    KEY idx_ledger_change_log_entity (entity_type, entity_uuid),
    KEY idx_ledger_change_log_operator_user_id (operator_user_id),
    CONSTRAINT fk_ledger_change_log_ledger_id FOREIGN KEY (ledger_id) REFERENCES ledger (id),
    CONSTRAINT fk_ledger_change_log_operator_user_id FOREIGN KEY (operator_user_id) REFERENCES user_account (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
