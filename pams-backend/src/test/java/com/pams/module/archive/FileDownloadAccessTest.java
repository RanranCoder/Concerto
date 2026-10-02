package com.pams.module.archive;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 审计 #2 文件下载 IDOR：/api/files/{id}/download 无对象级/部门级鉴权，fileId 自增可枚举。
 *
 * <p>可见性策略（与材料列表的部门过滤口径一致，红线「名单、函调、党员信息需部长以上」）：
 * <ul>
 *   <li>部长及以上（role.level &gt;= 3）：全部可见</li>
 *   <li>干事：上传者本人或同部门上传的文件可见</li>
 *   <li>敏感 bizType（签到/应签名单/排班名单/考勤/发票）：额外要求部长及以上</li>
 *   <li>越权与不存在同响应（4001 文件不存在），不给 ID 枚举留下存在性旁路</li>
 * </ul>
 * 预览与导出在前端统一走本接口（pams-web/src/components/file/httpBlob.ts 的 fetchFileBlob、
 * src/api/file.ts 的 fetchFileBlob/downloadFile 均 GET /api/files/{id}/download），
 * 因此本测试覆盖该接口即覆盖下载/预览/导出三个通道。
 *
 * <p>测试账号由 DataSeeder 在 test profile 固定种入（密码 123456）：
 * staff=干事（文秘部）、secleader=文秘部长（文秘部，level 3）、orgleader=组织部长（组织部，level 3）。
 *
 * <p>迁移目标：曾因 V25+ 的迁移含 MySQL 专有 SET @var/PREPARE 语法在 H2 上失败而限定 target=20；
 * 现已为 V25/V26/V27 提供 db/migration-h2 分支（见 application.yml 的 test profile），H2 可跑完整迁移链，
 * 故不再限定 target，避免实体（V21 的 schedule.dept_id、V30 的 sys_department.org_id 等）与旧 schema 漂移。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FileDownloadAccessTest {

    @Autowired
    MockMvc mvc;

    private String login(String username) throws Exception {
        MvcResult res = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"123456\"}"))
            .andExpect(jsonPath("$.data.token").isNotEmpty())
            .andReturn();
        String body = res.getResponse().getContentAsString();
        return body.split("\"token\":\"")[1].split("\"")[0];
    }

    private String bearer(String username) throws Exception {
        return "Bearer " + login(username);
    }

    /** 以指定账号上传文件（登录即可上传），返回 file_record.id */
    private long uploadAs(String username, String filename, String bizType) throws Exception {
        MvcResult res = mvc.perform(multipart("/api/files/upload")
                .file(new MockMultipartFile("file", filename, "application/octet-stream",
                        "sample-bytes".getBytes(StandardCharsets.UTF_8)))
                .param("bizType", bizType)
                .header("Authorization", bearer(username)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").isNotEmpty())
            .andReturn();
        String body = res.getResponse().getContentAsString();
        return Long.parseLong(body.split("\"id\":")[1].split("[^0-9]")[0]);
    }

    // ==================== ① 对象级/部门级鉴权 ====================

    @Test
    void staffDownloadOtherUploaderOtherDeptFile_denied() throws Exception {
        // 组织部的 orgleader 上传、文秘部的 staff 直连下载（IDOR 主场景）→ 越权，不可拖走他部门原件
        long fileId = uploadAs("orgleader", "他部门材料.txt", "OTHER");
        mvc.perform(get("/api/files/" + fileId + "/download")
                .header("Authorization", bearer("staff")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(4001))
            .andExpect(jsonPath("$.message").value("文件不存在"));
    }

    @Test
    void staffDownloadOwnFile_allowed() throws Exception {
        // 本人上传的文件（非敏感）始终可下载
        long fileId = uploadAs("staff", "本人材料.txt", "OTHER");
        mvc.perform(get("/api/files/" + fileId + "/download")
                .header("Authorization", bearer("staff")))
            .andExpect(status().isOk())
            .andExpect(content().bytes("sample-bytes".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void staffDownloadSameDeptOtherUploaderFile_allowed() throws Exception {
        // 与材料列表部门过滤口径一致：同部门他人上传的非敏感文件对干事可见（UI 行为保持）
        long fileId = uploadAs("secleader", "同部门材料.txt", "OTHER");
        mvc.perform(get("/api/files/" + fileId + "/download")
                .header("Authorization", bearer("staff")))
            .andExpect(status().isOk())
            .andExpect(content().bytes("sample-bytes".getBytes(StandardCharsets.UTF_8)));
    }

    // ==================== ② 敏感 bizType 额外要求部长及以上 ====================

    @Test
    void staffDownloadSensitiveSigninOwnFile_denied() throws Exception {
        // 签到表（名单类，红线敏感件）：即使是本人上传，干事也无权下载
        long fileId = uploadAs("staff", "签到表.txt", "SIGNIN");
        mvc.perform(get("/api/files/" + fileId + "/download")
                .header("Authorization", bearer("staff")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(4001));
    }

    @Test
    void staffDownloadSensitiveInvoiceOwnFile_denied() throws Exception {
        // 发票扫描件同为敏感 bizType，干事一律被拒
        long fileId = uploadAs("staff", "发票.txt", "INVOICE");
        mvc.perform(get("/api/files/" + fileId + "/download")
                .header("Authorization", bearer("staff")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(4001));
    }

    @Test
    void leaderDownloadSensitiveCrossDeptFile_allowed() throws Exception {
        // 部长（level 3）可下载敏感件：组织部长跨部门下载文秘部干事上传的签到表 → 放行
        long fileId = uploadAs("staff", "签到表-部长下载.txt", "SIGNIN");
        mvc.perform(get("/api/files/" + fileId + "/download")
                .header("Authorization", bearer("orgleader")))
            .andExpect(status().isOk())
            .andExpect(content().bytes("sample-bytes".getBytes(StandardCharsets.UTF_8)));
    }

    // ==================== ③ 预览通道（前端 FilePreviewModal 同走本接口） ====================

    @Test
    void staffPreviewOtherDeptFile_denied() throws Exception {
        // 预览（httpBlob.fetchFileBlob → GET /api/files/{id}/download）与下载同通道同策略，不得成为绕过旁路
        long fileId = uploadAs("orgleader", "他部门预览件.txt", "OTHER");
        mvc.perform(get("/api/files/" + fileId + "/download")
                .header("Authorization", bearer("staff")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(4001));
    }

    // ==================== ④ 越权响应不泄露文件存在性（防 ID 枚举） ====================

    @Test
    void forbiddenFileResponseEqualsMissingFileResponse() throws Exception {
        // 越权与不存在必须同响应：否则可枚举 fileId 探测哪些文件存在
        long fileId = uploadAs("orgleader", "他部门机密.txt", "OTHER");
        MvcResult missing = mvc.perform(get("/api/files/99999999/download")
                .header("Authorization", bearer("staff")))
            .andReturn();
        MvcResult forbidden = mvc.perform(get("/api/files/" + fileId + "/download")
                .header("Authorization", bearer("staff")))
            .andReturn();
        assertThat(forbidden.getResponse().getContentAsString())
            .isEqualTo(missing.getResponse().getContentAsString());
    }
}
