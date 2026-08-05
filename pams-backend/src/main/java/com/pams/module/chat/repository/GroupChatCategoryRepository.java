package com.pams.module.chat.repository;

import com.pams.module.chat.entity.GroupChatCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GroupChatCategoryRepository extends JpaRepository<GroupChatCategory, Long> {
    List<GroupChatCategory> findAllByOrderBySortOrderAscIdAsc();
}
