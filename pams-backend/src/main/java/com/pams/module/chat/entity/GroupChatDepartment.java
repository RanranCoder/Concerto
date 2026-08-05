package com.pams.module.chat.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.io.Serializable;

/**
 * 群聊-部门多对多关联（PRD F06）
 */
@Data
@Entity
@Table(name = "group_chat_department")
@IdClass(GroupChatDepartmentId.class)
public class GroupChatDepartment implements Serializable {
    @Id
    @Column(name = "group_chat_id")
    private Long groupChatId;

    @Id
    @Column(length = 50)
    private String department;
}
