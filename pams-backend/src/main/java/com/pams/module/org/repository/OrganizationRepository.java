package com.pams.module.org.repository;

import com.pams.module.org.entity.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface OrganizationRepository extends JpaRepository<Organization, Long> {
    Optional<Organization> findByName(String name);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Organization o where o.id = :id")
    Optional<Organization> findForUpdate(@Param("id") long id);

    /** 启用中的组织列表（登录页公开组织选择用）。deleted=0 由实体 @SQLRestriction 兜底。 */
    List<Organization> findByStatusOrderByIdAsc(Integer status);
}
