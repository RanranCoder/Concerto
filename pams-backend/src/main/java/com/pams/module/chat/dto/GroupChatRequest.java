package com.pams.module.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class GroupChatRequest {

    @NotBlank(message = "群聊名称不能为空")
    @Size(max = 50)
    private String name;

    private Long categoryId;

    private Long activityId;

    /** 关联部门列表（部门枚举：文秘部/组织部/新媒体中心/青年科技部） */
    private List<String> departments;

    private Long ownerId;

    @Size(max = 500)
    private String qrCodeUrl;

    @Size(max = 200)
    private String remark;

    /** ACTIVE / DISSOLVED / ARCHIVED */
    private String status = "ACTIVE";
}
