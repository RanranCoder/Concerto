package com.pams.module.member.dto;

/** 一键导入账号结果：成功创建数 / 跳过数。 */
public record AccountImportResultVO(int created, int skipped) {}
