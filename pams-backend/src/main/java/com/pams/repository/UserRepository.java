package com.pams.repository;

import com.pams.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"dept", "role"})
    List<User> findByOrgIdOrderByIdAsc(Long orgId);
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"dept", "role"})
    List<User> findByOrgIdAndDeptIdAndStatusOrderByIdAsc(Long orgId, Long deptId, Integer status);
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);
    List<User> findByDeptId(Long deptId);
    List<User> findByRoleCode(String roleCode);

    // 多租户（A3）：账号表未自动过滤，通知广播等按组织取收件人，避免跨组织触达
    List<User> findByOrgIdAndDeptId(Long orgId, Long deptId);
    List<User> findByOrgIdAndRoleCode(Long orgId, String roleCode);
    List<User> findByStudentNo(String studentNo);

    /** 查已软删的同名账号（native query 绕过 @SQLRestriction，供 createUser 复活用）。 */
    @Query(value = "SELECT * FROM sys_user WHERE username = :username AND deleted <> 0 ORDER BY id DESC LIMIT 1", nativeQuery = true)
    Optional<User> findDeletedByUsername(@Param("username") String username);
}
