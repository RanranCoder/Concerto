package com.pams.module.chat.repository;

import com.pams.module.chat.entity.GroupChat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GroupChatRepository extends JpaRepository<GroupChat, Long> {
    List<GroupChat> findAllByOrderByCreatedAtDesc();
    List<GroupChat> findByStatusOrderByCreatedAtDesc(String status);
}
