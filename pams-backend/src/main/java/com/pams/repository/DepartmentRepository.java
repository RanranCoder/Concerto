package com.pams.repository;

import com.pams.entity.Department;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DepartmentRepository extends JpaRepository<Department, Long> {

    /** 某组织的全部架构节点（按层级、排序返回，供服务端组装成树）。 */
    List<Department> findByOrgIdOrderBySortOrderAscIdAsc(Long orgId);

    List<Department> findByOrgIdAndParentId(Long orgId, Long parentId);

    boolean existsByOrgIdAndName(Long orgId, String name);

    boolean existsByParentId(Long parentId);
}
