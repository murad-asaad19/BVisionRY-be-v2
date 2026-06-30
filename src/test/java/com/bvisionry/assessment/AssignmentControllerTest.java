package com.bvisionry.assessment;

import com.bvisionry.aiconfig.service.RateLimitService;
import com.bvisionry.assessment.dto.AssignmentResponse;
import com.bvisionry.assessment.dto.CreateAssignmentRequest;
import com.bvisionry.auth.CookieService;
import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.jwt.JwtProvider;
import com.bvisionry.common.enums.PipelineStatus;
import com.bvisionry.common.enums.SubmissionStatus;
import com.bvisionry.common.web.ClientIpResolver;
import com.bvisionry.evaluation.PillarReeditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AssignmentController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class})
class AssignmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @MockitoBean
    private AssignmentService assignmentService;

    @MockitoBean
    private PillarReeditService pillarReeditService;

    @MockitoBean
    private AdminAnswerOverrideService adminAnswerOverrideService;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private CookieService cookieService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private RateLimitService rateLimitService;

    @MockitoBean
    private ClientIpResolver clientIpResolver;

    @Test
    void createAssignment_returns201() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();
        UUID assignedBy = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        CreateAssignmentRequest request = new CreateAssignmentRequest(
                pipelineId, null, null, null, false, false, null);

        AssignmentResponse response = new AssignmentResponse(
                UUID.randomUUID(), pipelineId, "Test Pipeline",
                PipelineStatus.PUBLISHED, orgId,
                userId, "Alice", "alice@test.com",
                assignedBy, null, UUID.randomUUID(), SubmissionStatus.IN_PROGRESS,
                Instant.now(), 1, 1);

        when(assignmentService.createAssignment(eq(orgId), any())).thenReturn(List.of(response));

        mockMvc.perform(post("/api/organizations/{orgId}/assignments", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].pipelineName").value("Test Pipeline"))
                .andExpect(jsonPath("$[0].userName").value("Alice"));
    }

    @Test
    void createAssignment_provisionOnlyBodyOmittingOptionalBooleans_returns201() throws Exception {
        // Regression: the front-end provision-only payload sends neither
        // autoAssignFutureMembers nor memberIds. Those map to record components
        // that must deserialize cleanly when ABSENT (boxed Boolean + compact
        // constructor) — otherwise Jackson 400s with "Cannot map null into
        // type boolean", surfacing as "Request body is malformed".
        UUID orgId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();

        AssignmentResponse response = new AssignmentResponse(
                UUID.randomUUID(), pipelineId, "Provisioned Pipeline",
                PipelineStatus.PUBLISHED, orgId,
                null, null, null,
                UUID.randomUUID(), null, null, null,
                Instant.now(), 1, 0);

        when(assignmentService.createAssignment(eq(orgId), any())).thenReturn(List.of(response));

        // Raw JSON mirroring the dialog: only pipelineId, assignToOrganization, maxCheckIns.
        String body = "{\"pipelineId\":\"" + pipelineId + "\",\"assignToOrganization\":true,\"maxCheckIns\":1}";

        mockMvc.perform(post("/api/organizations/{orgId}/assignments", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].pipelineName").value("Provisioned Pipeline"))
                .andExpect(jsonPath("$[0].userId").doesNotExist());
    }

    @Test
    void listAssignments_returns200() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        AssignmentResponse response = new AssignmentResponse(
                UUID.randomUUID(), UUID.randomUUID(), "Pipeline",
                PipelineStatus.PUBLISHED, orgId,
                userId, "Bob", "bob@test.com",
                UUID.randomUUID(), null, UUID.randomUUID(), SubmissionStatus.EVALUATED,
                Instant.now(), 1, 1);

        when(assignmentService.listAssignments(orgId, null)).thenReturn(List.of(response));

        mockMvc.perform(get("/api/organizations/{orgId}/assignments", orgId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].pipelineName").value("Pipeline"))
                .andExpect(jsonPath("$[0].status").value("EVALUATED"));
    }

    @Test
    void cancelAssignment_returns204() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();

        mockMvc.perform(delete("/api/organizations/{orgId}/assignments/{id}", orgId, assignmentId))
                .andExpect(status().isNoContent());

        verify(assignmentService).cancelAssignment(orgId, assignmentId);
    }
}
