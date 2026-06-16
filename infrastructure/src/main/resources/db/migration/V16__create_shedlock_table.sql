-- ShedLock distributed lock table for multi-replica @Scheduled coordination (#89).
-- No RLS: global state, not tenant-scoped.
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
