package com.pams.module.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 人工录入文档的请求体（方案 §2.6 的 {@code ManualDocSource}）。
 *
 * <p>刻意让 {@code visibility} 与 {@code effectiveFrom/To} 显式出现在请求里：
 * 它们是**知识库与普通文档最重要的区别**——知识库的答案要能回答
 * "这条规定现在还有效吗"，而这个能力完全依赖录入时填了生效期。
 * 不给默认值、不在前端偷偷补，是为了让录入者意识到这一步。
 */
public record KbDocumentRequest(

        @NotBlank(message = "标题不能为空")
        @Size(max = 200, message = "标题不能超过 200 字")
        String title,

        /** REGULATION / POLICY / MANUAL / FAQ */
        String docType,

        @Size(max = 50)
        String category,

        @NotBlank(message = "正文不能为空")
        String body,

        @Size(max = 500)
        String summary,

        @Size(max = 30)
        String version,

        LocalDate effectiveFrom,

        /** 废止日；null = 长期有效 */
        LocalDate effectiveTo,

        /** ALL / LEADER */
        String visibility,

        /** 附加权限码要求（可选） */
        String requiredPermission,

        /** DRAFT / PUBLISHED / ARCHIVED */
        String status
) {}
