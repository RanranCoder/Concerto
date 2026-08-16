package com.pams.module.member;

import com.pams.module.member.entity.Member;
import com.pams.module.member.entity.MemberSession;
import com.pams.module.member.repository.MemberRepository;
import com.pams.module.member.repository.MemberSessionRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MemberIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired MemberRepository memberRepo;
    @Autowired MemberSessionRepository sessionRepo;

    private String login(String username) throws Exception {
        MvcResult res = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"123456\"}")).andReturn();
        String body = res.getResponse().getContentAsString();
        return body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    private byte[] rosterXlsx() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("S");
            String[][] rows = {
                {"序号", "部门", "职位", "姓名", "性别", "学号", "班级", "联系方式", "政治面貌"},
                {"1", "文秘部", "干事", "集成测试甲", "男", "2990000001", "25测试班", "13000000000", "群众"},
            };
            for (int i = 0; i < rows.length; i++) {
                Row r = sheet.createRow(i);
                for (int j = 0; j < rows[i].length; j++) r.createCell(j).setCellValue(rows[i][j]);
            }
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) { wb.write(out); return out.toByteArray(); }
        }
    }

    @Test
    void staffGets403_andDirectorCanCreateSessionImportListArchive() throws Exception {
        String staffToken = login("staff");
        mvc.perform(get("/api/members").header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isForbidden());

        String token = login("zhuren");

        // 建届别（用唯一届名，避免与 Task 12 种子届「第九届」在共享 H2 上冲突）
        MvcResult sres = mvc.perform(post("/api/member-sessions").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"集成测试届\"}"))
                .andExpect(status().isOk()).andReturn();
        String sessionId = sessionRepo.findAll().stream()
                .filter(s -> "集成测试届".equals(s.getName())).findFirst().orElseThrow().getId().toString();

        // 导入成员
        MockMultipartFile file = new MockMultipartFile("file", "members.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", rosterXlsx());
        mvc.perform(multipart("/api/members/import").file(file).param("sessionId", sessionId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.success").value(1));

        // 列表含该成员
        mvc.perform(get("/api/members").param("sessionId", sessionId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].name").value("集成测试甲"));

        // 换届归档 → 在职变往届
        mvc.perform(post("/api/members/" + sessionId + "/archive")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.count").value(1));
        Member m = memberRepo.findBySessionId(Long.valueOf(sessionId)).get(0);
        assertThat(m.getStatus()).isEqualTo("ALUMNI");
    }
}
