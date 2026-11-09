package com.lostfound.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "deployment_audit")
public class DeploymentAudit {
    @Id
    @Column(length = 100)
    public String deploymentId;
    public String processKey;
    public String deployedBy;
    public String fileName;
    public Instant deployedAt;

    protected DeploymentAudit() {}
    public DeploymentAudit(String deploymentId, String processKey, String deployedBy, String fileName) {
        this.deploymentId = deploymentId;
        this.processKey = processKey;
        this.deployedBy = deployedBy;
        this.fileName = fileName;
        this.deployedAt = Instant.now();
    }
}
