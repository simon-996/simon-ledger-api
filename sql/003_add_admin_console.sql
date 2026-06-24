USE simon_ledger;

CREATE TABLE IF NOT EXISTS admin_user
(
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    uuid           VARCHAR(64)  NOT NULL,
    account        VARCHAR(64)  NOT NULL,
    password_hash  VARCHAR(255) NOT NULL,
    nickname       VARCHAR(64)  NOT NULL,
    role           VARCHAR(32)  NOT NULL DEFAULT 'super_admin',
    status         TINYINT      NOT NULL DEFAULT 1 COMMENT '1 normal, 2 disabled',
    last_login_at  DATETIME     NULL,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at     DATETIME     NULL,
    UNIQUE KEY uk_admin_user_uuid (uuid),
    UNIQUE KEY uk_admin_user_account (account),
    KEY idx_admin_user_status (status),
    KEY idx_admin_user_deleted_at (deleted_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS admin_operation_log
(
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    uuid          VARCHAR(64)  NOT NULL,
    admin_user_id BIGINT       NOT NULL,
    action        VARCHAR(64)  NOT NULL,
    target_type   VARCHAR(64)  NULL,
    target_uuid   VARCHAR(64)  NULL,
    detail        VARCHAR(512) NULL,
    ip_address    VARCHAR(64)  NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_admin_operation_log_uuid (uuid),
    KEY idx_admin_operation_log_admin_user_id (admin_user_id),
    KEY idx_admin_operation_log_created_at (created_at),
    CONSTRAINT fk_admin_operation_log_admin_user_id FOREIGN KEY (admin_user_id) REFERENCES admin_user (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- Create the first admin manually after generating a BCrypt password hash.
-- Example:
-- INSERT INTO admin_user (uuid, account, password_hash, nickname, role, status)
-- VALUES (REPLACE(UUID(), '-', ''), 'admin', '<BCrypt hash>', '管理员', 'super_admin', 1);
