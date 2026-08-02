package com.pams.module.party.entity;

/**
 * 党员发展流程阶段。存字符串（EnumType.STRING），label() 返回中文身份。
 */
public enum PartyStageType {
    APPLICANT, ACTIVE, DEVELOPMENT, PROBATIONARY, FULL;

    public String label() {
        return switch (this) {
            case APPLICANT -> "入党申请人";
            case ACTIVE -> "入党积极分子";
            case DEVELOPMENT -> "重点发展对象";
            case PROBATIONARY -> "预备党员";
            case FULL -> "正式党员";
        };
    }
}
