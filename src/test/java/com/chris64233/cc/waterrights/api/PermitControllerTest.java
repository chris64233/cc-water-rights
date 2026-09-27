package com.chris64233.cc.waterrights.api;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import com.chris64233.cc.waterrights.repository.UsageEventRepository;
import com.chris64233.cc.waterrights.repository.WaterPermitRepository;

@SpringBootTest
@AutoConfigureMockMvc
class PermitControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UsageEventRepository eventRepository;
    @Autowired
    private WaterPermitRepository permitRepository;

    @BeforeEach
    void cleanDatabase() {
        eventRepository.deleteAllInBatch();
        permitRepository.deleteAllInBatch();
    }

    private MvcResult createPermit(String body) throws Exception {
        return mockMvc.perform(post("/api/permits").contentType(APPLICATION_JSON).content(body))
                .andReturn();
    }

    private String validPermit(String no) {
        return """
                {"permitNo":"%s","owner":"张三","intakePoint":"一号取水口",
                 "startDate":"2026-04-01","endDate":"2026-09-30","authorizedVolume":"100.000"}
                """.formatted(no);
    }

    @Test
    void createPermit_success() throws Exception {
        mockMvc.perform(post("/api/permits").contentType(APPLICATION_JSON).content(validPermit("W-1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.permitNo").value("W-1"))
                .andExpect(jsonPath("$.authorizedVolume").value(100.000))
                .andExpect(jsonPath("$.remainingVolume").value(100.000))
                .andExpect(jsonPath("$.events").isArray());
    }

    @Test
    void createPermit_validationError_unifiedJson() throws Exception {
        String body = """
                {"permitNo":"","owner":"","intakePoint":"",
                 "startDate":"2026-09-30","endDate":"2026-04-01","authorizedVolume":"0"}
                """;
        mockMvc.perform(post("/api/permits").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("请求参数校验失败"))
                .andExpect(jsonPath("$.fieldErrors.permitNo").exists())
                .andExpect(jsonPath("$.fieldErrors.authorizedVolume").exists());
    }

    @Test
    void createPermit_malformedJson_unifiedJson() throws Exception {
        mockMvc.perform(post("/api/permits").contentType(APPLICATION_JSON).content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void createPermit_duplicate_conflict() throws Exception {
        createPermit(validPermit("W-DUP"));
        mockMvc.perform(post("/api/permits").contentType(APPLICATION_JSON).content(validPermit("W-DUP")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERMIT_NO_DUPLICATED"))
                .andExpect(jsonPath("$.message").isString());
    }

    @Test
    void createPermit_invalidDateRange_422() throws Exception {
        String body = """
                {"permitNo":"W-DR","owner":"张三","intakePoint":"口",
                 "startDate":"2026-09-30","endDate":"2026-04-01","authorizedVolume":"100"}
                """;
        mockMvc.perform(post("/api/permits").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
    }

    @Test
    void declareThenRevert_fullLifecycle() throws Exception {
        createPermit(validPermit("W-LIFE"));

        // 申报
        mockMvc.perform(post("/api/permits/W-LIFE/declarations")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"externalEventNo":"D-1","occurrenceDate":"2026-05-01","volume":"60"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.event.type").value("DECLARATION"))
                .andExpect(jsonPath("$.replayed").value(false));

        // 重放返回 200
        mockMvc.perform(post("/api/permits/W-LIFE/declarations")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"externalEventNo":"D-1","occurrenceDate":"2026-05-01","volume":"60"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));

        // 内容不同 -> 409
        mockMvc.perform(post("/api/permits/W-LIFE/declarations")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"externalEventNo":"D-1","occurrenceDate":"2026-05-02","volume":"60"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONTENT_CONFLICT"));

        // 超额 -> 422
        mockMvc.perform(post("/api/permits/W-LIFE/declarations")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"externalEventNo":"D-2","occurrenceDate":"2026-05-02","volume":"40.001"}
                                """))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("QUOTA_EXCEEDED"));

        // 冲正
        mockMvc.perform(post("/api/permits/W-LIFE/reversals")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"externalEventNo":"R-1","originalEventNo":"D-1",
                                 "occurrenceDate":"2026-10-05","volume":"60"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.event.type").value("REVERSAL"))
                .andExpect(jsonPath("$.event.originalEventNo").value("D-1"));

        // 冲正后额度恢复
        mockMvc.perform(get("/api/permits/W-LIFE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizedVolume").value(100.000))
                .andExpect(jsonPath("$.effectiveUsedVolume").value(0.000))
                .andExpect(jsonPath("$.reversedVolume").value(60.000))
                .andExpect(jsonPath("$.remainingVolume").value(100.000))
                .andExpect(jsonPath("$.events.length()").value(2))
                .andExpect(jsonPath("$.events[0].externalEventNo").value("D-1"))
                .andExpect(jsonPath("$.events[0].reversed").value(true))
                .andExpect(jsonPath("$.events[1].externalEventNo").value("R-1"))
                .andExpect(jsonPath("$.events[1].reversed").value(false));
    }

    @Test
    void declare_unknownPermit_404() throws Exception {
        mockMvc.perform(post("/api/permits/GHOST/declarations")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"externalEventNo":"D-1","occurrenceDate":"2026-05-01","volume":"1"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PERMIT_NOT_FOUND"));
    }

    @Test
    void reverse_reversalAgain_422() throws Exception {
        createPermit(validPermit("W-RR"));
        mockMvc.perform(post("/api/permits/W-RR/declarations").contentType(APPLICATION_JSON)
                .content("""
                        {"externalEventNo":"D-1","occurrenceDate":"2026-05-01","volume":"10"}
                        """)).andExpect(status().isCreated());
        mockMvc.perform(post("/api/permits/W-RR/reversals").contentType(APPLICATION_JSON)
                .content("""
                        {"externalEventNo":"R-1","originalEventNo":"D-1",
                         "occurrenceDate":"2026-10-01","volume":"10"}
                        """)).andExpect(status().isCreated());

        mockMvc.perform(post("/api/permits/W-RR/reversals").contentType(APPLICATION_JSON)
                        .content("""
                                {"externalEventNo":"R-2","originalEventNo":"R-1",
                                 "occurrenceDate":"2026-10-02","volume":"10"}
                                """))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("REVERSAL_NOT_REVERSIBLE"));
    }
}
