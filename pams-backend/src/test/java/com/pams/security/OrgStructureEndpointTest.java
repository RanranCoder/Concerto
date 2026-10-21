package com.pams.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrgStructureEndpointTest {
    @Autowired MockMvc mvc;

    private String login(String username) throws Exception {
        String response = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"123456\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return response.split("\"token\":\"")[1].split("\"")[0];
    }

    @Test
    void managerCanReadMinimalDirectoryWithoutAccountSecrets() throws Exception {
        mvc.perform(get("/api/orgs/current/structure").header("Authorization", "Bearer " + login("teacher")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.units").isArray())
                .andExpect(jsonPath("$.data.accounts").isArray())
                .andExpect(jsonPath("$.data.accounts[0].username").isNotEmpty())
                .andExpect(jsonPath("$.data.accounts[0].password").doesNotExist())
                .andExpect(jsonPath("$.data.accounts[0].phone").doesNotExist());
    }
    @Test
    void anonymousCannotReadManagementDirectory() throws Exception {
        mvc.perform(get("/api/orgs/current/structure")).andExpect(status().isUnauthorized());
    }
    @Test
    void ordinaryMemberCannotReadManagementDirectory() throws Exception {
        mvc.perform(get("/api/orgs/current/structure").header("Authorization", "Bearer " + login("staff"))).andExpect(status().isForbidden());
    }
}
