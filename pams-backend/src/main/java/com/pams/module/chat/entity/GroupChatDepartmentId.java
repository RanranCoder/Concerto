package com.pams.module.chat.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupChatDepartmentId implements Serializable {
    private Long groupChatId;
    private String department;
}
