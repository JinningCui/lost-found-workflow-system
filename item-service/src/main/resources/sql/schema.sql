-- Execute inside item_db. Compose normally creates these tables via Hibernate ddl-auto=update.
CREATE TABLE IF NOT EXISTS items (
  id varchar(36) PRIMARY KEY,
  title varchar(160) NOT NULL,
  category varchar(80) NOT NULL,
  public_description varchar(2000) NOT NULL,
  found_location varchar(200) NOT NULL,
  found_at datetime(6) NOT NULL,
  valuable bit(1) NOT NULL,
  status varchar(24) NOT NULL,
  reserved_claim_id varchar(36),
  creation_operation_id varchar(100) UNIQUE,
  created_at datetime(6) NOT NULL,
  updated_at datetime(6) NOT NULL,
  INDEX idx_item_search (status, category, created_at),
  INDEX idx_item_found_at (found_at)
);

CREATE TABLE IF NOT EXISTS item_transitions (
  operation_id varchar(100) PRIMARY KEY,
  item_id varchar(36) NOT NULL,
  from_status varchar(24) NOT NULL,
  to_status varchar(24) NOT NULL,
  claim_id varchar(36),
  created_at datetime(6) NOT NULL,
  INDEX idx_item_transition_item (item_id, created_at)
);
