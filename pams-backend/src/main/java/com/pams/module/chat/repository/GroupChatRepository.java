package com.pams.module.chat.repository;

import com.pams.module.chat.entity.GroupChat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface GroupChatRepository extends JpaRepository<GroupChat, Long> {
    List<GroupChat> findAllByOrderByCreatedAtDesc();
    List<GroupChat> findByStatusOrderByCreatedAtDesc(String status);

    /** 删除分类前将引用该分类的群聊 category_id 置空，避免 group_chat.category_id 残留孤儿引用 */
    @Modifying
    @Query("UPDATE GroupChat c SET c.categoryId = null WHERE c.categoryId = :categoryId")
    void clearCategoryId(@Param("categoryId") Long categoryId);
}
