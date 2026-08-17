package com.pams.module.member.dto;

import java.util.Map;

/** 成员职位/状态枚举：中文标签 <-> 枚举码。集中一处，导入/校验/前端共用。 */
public final class MemberEnums {
    private MemberEnums() {}

    public static final Map<String, String> POSITION_LABELS = Map.of(
        "DIRECTOR", "主任", "SUB_DIRECTOR", "副主任", "DEPT_HEAD", "部长",
        "SUB_DEPT_HEAD", "副部长", "STAFF", "干事");

    public static final Map<String, String> STATUS_LABELS = Map.of(
        "ACTIVE", "在职", "ALUMNI", "往届", "RESIGNED", "退部",
        "EXPELLED", "开除", "LEFT", "离职");

    public static boolean isPosition(String code) { return POSITION_LABELS.containsKey(code); }
    public static boolean isStatus(String code) { return STATUS_LABELS.containsKey(code); }

    /** 中文 -> 码；未知返回 null */
    public static String positionOf(String label) {
        for (var e : POSITION_LABELS.entrySet()) if (e.getValue().equals(label)) return e.getKey();
        return null;
    }
    public static String statusOf(String label) {
        for (var e : STATUS_LABELS.entrySet()) if (e.getValue().equals(label)) return e.getKey();
        return null;
    }
}
