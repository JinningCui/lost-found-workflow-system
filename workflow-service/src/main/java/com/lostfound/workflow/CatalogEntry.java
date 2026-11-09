package com.lostfound.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "service_catalog")
public class CatalogEntry {
    @Id
    @Column(length = 100)
    public String serviceName;
    public boolean enabled;
    public Instant registeredAt;
    public Instant updatedAt;

    protected CatalogEntry() {}
    public CatalogEntry(String serviceName, boolean enabled) {
        this.serviceName = serviceName;
        this.enabled = enabled;
        this.registeredAt = Instant.now();
        this.updatedAt = registeredAt;
    }
}
