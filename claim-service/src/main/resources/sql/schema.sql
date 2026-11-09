-- Execute inside claim_db. Compose normally creates these tables via Hibernate ddl-auto=update.
CREATE TABLE IF NOT EXISTS claims (
  id varchar(36) PRIMARY KEY,
  item_id varchar(36) NOT NULL,
  claimant_id varchar(100) NOT NULL,
  description varchar(2000) NOT NULL,
  status varchar(24) NOT NULL,
  creation_operation_id varchar(100) UNIQUE,
  created_at datetime(6) NOT NULL,
  updated_at datetime(6) NOT NULL,
  version bigint NOT NULL,
  INDEX idx_claim_item (item_id, created_at),
  INDEX idx_claim_claimant (claimant_id, created_at)
);

CREATE TABLE IF NOT EXISTS claim_status_events (
  operation_id varchar(100) PRIMARY KEY,
  claim_id varchar(36) NOT NULL,
  from_status varchar(24) NOT NULL,
  to_status varchar(24) NOT NULL,
  created_at datetime(6) NOT NULL,
  INDEX idx_claim_status_claim (claim_id, created_at)
);
