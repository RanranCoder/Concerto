package com.pams.module.org.repository;

import com.pams.module.org.entity.OrgPosition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrgPositionRepository extends JpaRepository<OrgPosition, Long> {
    List<OrgPosition> findByOrgIdOrderBySortOrderAsc(Long orgId);
}
