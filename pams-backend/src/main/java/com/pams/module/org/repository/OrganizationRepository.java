package com.pams.module.org.repository;

import com.pams.module.org.entity.Organization;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrganizationRepository extends JpaRepository<Organization, Long> {
    Optional<Organization> findByName(String name);

    /** 启用中的组织列表（登录页公开组织选择用）。deleted=0 由实体 @SQLRestriction 兜底。 */
    List<Organization> findByStatusOrderByIdAsc(Integer status);
}
