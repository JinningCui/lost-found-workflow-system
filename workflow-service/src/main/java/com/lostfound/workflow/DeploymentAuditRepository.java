package com.lostfound.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DeploymentAuditRepository extends JpaRepository<DeploymentAudit, String> {}
