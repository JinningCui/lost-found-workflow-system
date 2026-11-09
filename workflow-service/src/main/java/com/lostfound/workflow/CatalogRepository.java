package com.lostfound.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogRepository extends JpaRepository<CatalogEntry, String> {}
