package com.pams.module.member.dto;

import java.util.List;

/** Excel 成员名单导入结果：总数 / 成功 / 跳过 / 失败行明细。 */
public record MemberImportResultVO(int total, int success, int skipped, List<MemberImportFailureVO> failed) {
    /** 单行导入失败：Excel 行号（1-based）、姓名、原因。 */
    public record MemberImportFailureVO(int row, String name, String reason) {}
}
