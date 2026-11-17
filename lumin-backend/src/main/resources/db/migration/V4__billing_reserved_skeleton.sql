-- =====================================================================
-- V4：付费实体骨架（docs/04 §6.2；schema-only，P0 无创建者/无 HTTP 入口/
-- 无权限授予——「空表 ≠ 交易已可用」，01 DEC-06 / 07 §2.4 T38）
-- 金额 BIGINT 分整数，币种固定 CNY；负数仅 settlement.net_minor。
-- =====================================================================

-- 1. billing_account（04 §6.2）：U(owner_id,kind,currency)
CREATE TABLE billing_account (
    id          CHAR(36)    NOT NULL,
    owner_id    CHAR(36)    NOT NULL,
    kind        VARCHAR(24) NOT NULL,
    currency    CHAR(3)     NOT NULL DEFAULT 'CNY',
    enabled     BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    created_by  CHAR(36)    NOT NULL,
    updated_by  CHAR(36)    NOT NULL,
    lock_version BIGINT     NOT NULL DEFAULT 1,
    CONSTRAINT pk_billing_account PRIMARY KEY (id),
    CONSTRAINT uq_billing_owner_kind UNIQUE (owner_id, kind, currency),
    CONSTRAINT ck_billing_account_kind CHECK (kind IN
        ('USER_CASH','COIN_LIABILITY','PLATFORM_REVENUE','AUTHOR_PAYABLE',
         'TAX_PAYABLE','REFUND_RESERVE','CHARGEBACK_LOSS','INCENTIVE_EXPENSE','CHANNEL_COST')),
    CONSTRAINT ck_billing_account_ccy CHECK (currency = 'CNY')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 2. billing_order（04 §6.2）：amount ≥ 0；P0 无创建者（state 仅枚举，无入口）
CREATE TABLE billing_order (
    id              CHAR(36)    NOT NULL,
    buyer_id        CHAR(36)    NOT NULL,
    product_type    VARCHAR(16) NOT NULL,
    product_id      CHAR(36)    NOT NULL,
    amount_minor    BIGINT      NOT NULL,
    currency        CHAR(3)     NOT NULL DEFAULT 'CNY',
    idempotency_key CHAR(36)    NOT NULL,
    state           VARCHAR(16) NOT NULL,
    created_at      DATETIME(6) NOT NULL,
    updated_at      DATETIME(6) NOT NULL,
    created_by      CHAR(36)    NOT NULL,
    updated_by      CHAR(36)    NOT NULL,
    lock_version    BIGINT      NOT NULL DEFAULT 1,
    CONSTRAINT pk_billing_order PRIMARY KEY (id),
    CONSTRAINT ck_billing_order_product CHECK (product_type IN ('CHAPTER','BOOK','MEMBERSHIP','GIFT')),
    CONSTRAINT ck_billing_order_state CHECK (state IN ('CREATED','PAID','CANCELLED','REFUNDED')),
    CONSTRAINT ck_billing_order_amount CHECK (amount_minor >= 0),
    CONSTRAINT ck_billing_order_ccy CHECK (currency = 'CNY')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 3. ledger_entry（04 §6.2）：append-only；amount > 0；同事务借贷相等为 B 类写入
--    门禁（应用层保证）；reversal_of 支持冲正
CREATE TABLE ledger_entry (
    id             CHAR(36)    NOT NULL,
    transaction_id CHAR(36)    NOT NULL,
    account_id     CHAR(36)    NOT NULL,
    direction      VARCHAR(8)  NOT NULL,
    amount_minor   BIGINT      NOT NULL,
    currency       CHAR(3)     NOT NULL DEFAULT 'CNY',
    reversal_of    CHAR(36)    NULL,
    created_at     DATETIME(6) NOT NULL,
    created_by     CHAR(36)    NOT NULL,
    CONSTRAINT pk_ledger_entry PRIMARY KEY (id),
    CONSTRAINT fk_ledger_account FOREIGN KEY (account_id) REFERENCES billing_account (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_ledger_direction CHECK (direction IN ('DEBIT','CREDIT')),
    CONSTRAINT ck_ledger_amount CHECK (amount_minor > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX idx_ledger_tx ON ledger_entry (transaction_id, account_id);

-- 4. revenue_share（04 §6.2）：state 五态；P0 仅 schema
CREATE TABLE revenue_share (
    id             CHAR(36)    NOT NULL,
    order_id       CHAR(36)    NOT NULL,
    beneficiary_id CHAR(36)    NOT NULL,
    rule_revision  BIGINT      NOT NULL,
    amount_minor   BIGINT      NOT NULL,
    state          VARCHAR(16) NOT NULL,
    created_at     DATETIME(6) NOT NULL,
    updated_at     DATETIME(6) NOT NULL,
    created_by     CHAR(36)    NOT NULL,
    updated_by     CHAR(36)    NOT NULL,
    lock_version   BIGINT      NOT NULL DEFAULT 1,
    CONSTRAINT pk_revenue_share PRIMARY KEY (id),
    CONSTRAINT ck_revenue_share_state CHECK (state IN ('PENDING','SETTLED','FROZEN','REVERSED')),
    CONSTRAINT ck_revenue_share_amount CHECK (amount_minor >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 5. settlement（04 §6.2）：U(author_id,period)；净额允许负值
CREATE TABLE settlement (
    id          CHAR(36)    NOT NULL,
    author_id   CHAR(36)    NOT NULL,
    period_start DATE       NOT NULL,
    period_end  DATE        NOT NULL,
    net_minor   BIGINT      NOT NULL,
    state       VARCHAR(16) NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    created_by  CHAR(36)    NOT NULL,
    updated_by  CHAR(36)    NOT NULL,
    lock_version BIGINT     NOT NULL DEFAULT 1,
    CONSTRAINT pk_settlement PRIMARY KEY (id),
    CONSTRAINT uq_settlement_author_period UNIQUE (author_id, period_start, period_end),
    CONSTRAINT ck_settlement_state CHECK (state IN ('DRAFT','APPROVED','PAID','FAILED')),
    CONSTRAINT ck_settlement_period CHECK (period_end >= period_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 6. refund（04 §6.2）：amount > 0；禁止自批（requested_by ≠ approved_by，应用层）
CREATE TABLE refund (
    id          CHAR(36)    NOT NULL,
    order_id    CHAR(36)    NOT NULL,
    amount_minor BIGINT     NOT NULL,
    reason      VARCHAR(500) NOT NULL,
    state       VARCHAR(16) NOT NULL,
    requested_by CHAR(36)   NOT NULL,
    approved_by CHAR(36)    NULL,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    created_by  CHAR(36)    NOT NULL,
    updated_by  CHAR(36)    NOT NULL,
    lock_version BIGINT     NOT NULL DEFAULT 1,
    CONSTRAINT pk_refund PRIMARY KEY (id),
    CONSTRAINT ck_refund_state CHECK (state IN ('REQUESTED','APPROVED','PAID','REJECTED')),
    CONSTRAINT ck_refund_amount CHECK (amount_minor > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
