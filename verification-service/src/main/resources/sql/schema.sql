-- Execute inside verification_db. Ciphertext columns contain Base64 AES-256-GCM data.
CREATE TABLE IF NOT EXISTS hidden_clues (
  id varchar(36) PRIMARY KEY,
  item_id varchar(36) NOT NULL,
  question varchar(300) NOT NULL,
  answer_ciphertext varchar(2000) NOT NULL,
  created_at datetime(6) NOT NULL,
  INDEX idx_clue_item (item_id)
);

CREATE TABLE IF NOT EXISTS clue_batches (
  operation_id varchar(100) PRIMARY KEY,
  item_id varchar(36) NOT NULL,
  created_at datetime(6) NOT NULL
);

CREATE TABLE IF NOT EXISTS evidence (
  claim_id varchar(36) PRIMARY KEY,
  item_id varchar(36) NOT NULL,
  preliminary_passed bit(1) NOT NULL,
  matched int NOT NULL,
  total int NOT NULL,
  score double NOT NULL,
  operation_id varchar(100) NOT NULL UNIQUE,
  created_at datetime(6) NOT NULL,
  INDEX idx_evidence_item (item_id)
);

CREATE TABLE IF NOT EXISTS evidence_answers (
  id varchar(36) PRIMARY KEY,
  claim_id varchar(36) NOT NULL,
  question varchar(300) NOT NULL,
  answer_ciphertext varchar(2000) NOT NULL,
  INDEX idx_evidence_answer_claim (claim_id)
);

CREATE TABLE IF NOT EXISTS reviews (
  operation_id varchar(100) PRIMARY KEY,
  claim_id varchar(36) NOT NULL,
  reviewer_id varchar(100) NOT NULL,
  stage varchar(50) NOT NULL,
  decision varchar(30) NOT NULL,
  reason varchar(2000),
  created_at datetime(6) NOT NULL,
  INDEX idx_review_claim (claim_id, created_at)
);
