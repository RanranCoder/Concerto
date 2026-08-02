package com.pams.module.archive.repository;

import com.pams.module.archive.entity.Material;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface MaterialRepository extends JpaRepository<Material, Long>,
        JpaSpecificationExecutor<Material> {
}
