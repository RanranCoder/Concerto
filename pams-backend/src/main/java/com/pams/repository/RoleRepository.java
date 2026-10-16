package com.pams.repository;

import com.pams.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long> {
    Optional<Role> findByCodeAndOrgId(String code, Long orgId);
    default Optional<Role> findByCode(String code) {
        return findByCodeAndOrgId(code, com.pams.common.OrgContext.getOrDefault());
    }
}
