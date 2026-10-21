package com.pams.module.member.dto;

import java.util.List;

public final class AccountBatchVO {
    private AccountBatchVO() {}

    public record Row(Long memberId, String name, String username, String phone,
                      Long deptId, String deptName, Long roleId, String roleName,
                      long roleVersion, List<String> permissions, List<String> permissionNames) {}

    public record Preview(String id, String expiresAt, List<Row> rows) {}

    public record Account(long userId, String name, String username, String roleName,
                          String deptName, boolean activated, boolean canIssue) {}

    public record Result(String id, int created, List<Account> accounts) {}

    public record Credential(String name, String username, String roleName, String deptName,
                             String token, String expiresAt) {}
}
