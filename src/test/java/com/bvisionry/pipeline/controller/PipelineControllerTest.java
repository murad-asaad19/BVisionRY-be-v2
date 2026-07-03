package com.bvisionry.pipeline.controller;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.pipeline.repository.PipelineRepository;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import com.bvisionry.testsupport.TestAuthentication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@EnabledIfDockerAvailable
class PipelineControllerTest extends AbstractPostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private PipelineRepository pipelineRepository;
    @Autowired private UserRepository userRepository;

    private String adminId;

    @BeforeEach
    void setUp() {
        pipelineRepository.deleteAll();
        userRepository.deleteAll();
        User admin = TestAuthentication.authenticateAsSuperAdmin(userRepository);
        adminId = admin.getId().toString();
    }

    @AfterEach
    void clearAuth() {
        TestAuthentication.clear();
    }

    @Test
    void createPipeline_returns201() throws Exception {
        mockMvc.perform(post("/api/pipelines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Mindset Assessment", "description": "Test", "createdBy": "%s"}
                                """.formatted(adminId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name", is("Mindset Assessment")))
                .andExpect(jsonPath("$.status", is("DRAFT")))
                .andExpect(jsonPath("$.version", is(1)));
    }

    @Test
    void listPipelines_returnsAll() throws Exception {
        mockMvc.perform(post("/api/pipelines")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "Pipeline 1", "createdBy": "%s"}
                        """.formatted(adminId)));

        mockMvc.perform(get("/api/pipelines"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void createPipeline_blankName_returns400() throws Exception {
        mockMvc.perform(post("/api/pipelines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "", "createdBy": "%s"}
                                """.formatted(adminId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getPipeline_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/pipelines/" + java.util.UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }
}
