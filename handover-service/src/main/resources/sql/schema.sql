-- Execute inside handover_db. Pickup codes are kept only as keyed hashes.
CREATE TABLE IF NOT EXISTS pickup_codes (
  id varchar(36) PRIMARY KEY,
  item_id varchar(36) NOT NULL,
  claim_id varchar(36) NOT NULL,
  code_hash varchar(100) NOT NULL,
  status varchar(24) NOT NULL,
  attempts int NOT NULL,
  expires_at datetime(6) NOT NULL,
  issued_at datetime(6) NOT NULL,
  staff_id varchar(100),
  creation_operation_id varchar(100) UNIQUE,
  redemption_operation_id varchar(100) UNIQUE,
  INDEX idx_code_item_status (item_id, status),
  INDEX idx_code_claim_status (claim_id, status)
);

CREATE TABLE IF NOT EXISTS code_actions (
  operation_id varchar(100) PRIMARY KEY,
  action varchar(24) NOT NULL,
  item_id varchar(36) NOT NULL,
  claim_id varchar(36),
  created_at datetime(6) NOT NULL
);

CREATE TABLE IF NOT EXISTS handover_records (
  operation_id varchar(100) PRIMARY KEY,
  item_id varchar(36) NOT NULL,
  claim_id varchar(36) NOT NULL,
  staff_id varchar(100) NOT NULL,
  created_at datetime(6) NOT NULL,
  INDEX idx_handover_item (item_id)
);

CREATE TABLE IF NOT EXISTS transfer_records (
  operation_id varchar(100) PRIMARY KEY,
  item_id varchar(36) NOT NULL UNIQUE,
  destination varchar(300) NOT NULL,
  operator_id varchar(100) NOT NULL,
  created_at datetime(6) NOT NULL
);
