package com.pams.module.org.dto;

import lombok.Data;

import java.util.List;

/**
 * 组织注册请求（免登录）。对齐《阶段1实施清单》§5.2：
 * 组织信息 + 管理员账号 + 架构树 + 启用的领域包。
 */
@Data
public class OrgRegisterRequest {

    private OrgInfo org;
    private AdminInfo admin;
    private List<UnitNode> units;
    private List<String> enabledPacks;

    @Data
    public static class OrgInfo {
        private String name;
        private String type;
        private String shortName;
        /** 主题色，如 #2F6FED；落 organization.theme_json */
        private String themeColor;
    }

    @Data
    public static class AdminInfo {
        private String username;
        private String password;
        private String realName;
        private String phone;
    }

    @Data
    public static class UnitNode {
        private String name;
        /** 部门 / 中心 / 小组 / 分队；空则默认「部门」 */
        private String unitType;
        private Long leaderUserId;
        private List<UnitNode> children;
    }
}
