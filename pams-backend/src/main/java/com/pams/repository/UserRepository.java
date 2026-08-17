package com.pams.repository;

import com.pams.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);
    List<User> findByDeptId(Long deptId);
    List<User> findByRoleCode(String roleCode);
    List<User> findByStudentNo(String studentNo);
}
