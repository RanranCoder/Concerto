package com.pams.module.party;

import com.pams.module.party.repository.PartyRosterRepository;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 集成测试：真实 xlsx 名单经 POST /api/files/import 导入 party_roster。
 * 同时随 Spring 上下文启动在 H2 上执行 Flyway V1+V2，验证 V2 迁移可正常执行。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RosterImportIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PartyRosterRepository rosterRepo;

    private String loginToken() throws Exception {
        MvcResult res = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"zhuren\",\"password\":\"123456\"}"))
                .andReturn();
        String body = res.getResponse().getContentAsString();
        return body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    private byte[] rosterXlsx() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("Sheet1");
            String[][] data = {
                    {"学校党校培训班学员信息表（第40期）"},
                    {"序号", "学号/工号", "姓 名", "性别", "民族", "班级", "支部", "备注"},
                    {"1", "2435101020120", "吴苑", "女", "汉族", "24物联网班", "第一党支部", ""},
                    {"2", "2435102020329", "谭子豪", "男", "汉族", "24计算机网络技术3班", "第一党支部", ""},
            };
            for (int i = 0; i < data.length; i++) {
                Row r = sheet.createRow(i);
                for (int j = 0; j < data[i].length; j++) {
                    r.createCell(j).setCellValue(data[i][j]);
                }
            }
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                wb.write(out);
                return out.toByteArray();
            }
        }
    }

    @Test
    void importXlsx_writesRosterRows() throws Exception {
        String token = loginToken();
        MockMultipartFile file = new MockMultipartFile("file", "40期入党积极分子名单.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                rosterXlsx());

        mvc.perform(multipart("/api/files/import").file(file)
                        .param("type", "ACTIVE")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.added").value(2))
                .andExpect(jsonPath("$.data.skipped").value(0));

        List<?> all = rosterRepo.findByRosterType("ACTIVE");
        assertThat(all).hasSize(2);
    }

    /** 同一名单重复导入：第二次 skipped=2，added=0，DB 不产生重复行。 */
    @Test
    void importXlsx_reImportSkipsDuplicates() throws Exception {
        String token = loginToken();
        MockMultipartFile file = new MockMultipartFile("file", "40期入党积极分子名单.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                rosterXlsx());
        // 用独立 rosterType 避免与同一 H2 内其他用例互相污染。
        String type = "ACTIVE_DUP";

        mvc.perform(multipart("/api/files/import").file(file)
                        .param("type", type)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.added").value(2));

        mvc.perform(multipart("/api/files/import").file(file)
                        .param("type", type)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.added").value(0))
                .andExpect(jsonPath("$.data.skipped").value(2));

        List<?> all = rosterRepo.findByRosterType(type);
        assertThat(all).hasSize(2);
    }

    @Test
    void importXlsx_rejectsOfficeTempFile() throws Exception {
        String token = loginToken();
        MockMultipartFile file = new MockMultipartFile("file", "~$40期入党积极分子名单.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                rosterXlsx());

        mvc.perform(multipart("/api/files/import").file(file)
                        .param("type", "ACTIVE")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(4001))
                .andExpect(jsonPath("$.message").value("请勿上传 Office 临时文件"));
    }
}
