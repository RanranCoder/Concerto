package com.pams.module.chat.repository;

import com.pams.module.chat.entity.GroupChatDepartment;
import com.pams.module.chat.entity.GroupChatDepartmentId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GroupChatDepartmentRepository extends JpaRepository<GroupChatDepartment, GroupChatDepartmentId> {
    List<GroupChatDepartment> findByGroupChatId(Long groupChatId);
    void deleteByGroupChatId(Long groupChatId);
}
