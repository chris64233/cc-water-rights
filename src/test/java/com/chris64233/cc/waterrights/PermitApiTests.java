package com.chris64233.cc.waterrights;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PermitApiTests {

    @Autowired
    private MockMvc mockMvc;

    /** 每个测试方法独立实例，事件号加此后缀避免跨用例冲突（事件号全局唯一）。 */
    private final String run = UUID.randomUUID().toString().substring(0, 8);

    private String ev(String base) {
        return base + "-" + run;
    }

    private String uniquePermitNo() {
        return "P-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String createPermitJson(String permitNo, String approvedVolume) {
        return """
                {
                  "permitNo": "%s",
                  "holder": "灌区合作社",
                  "intakePoint": "东风渠3号闸",
                  "startDate": "2026-04-01",
                  "endDate": "2026-09-30",
                  "approvedVolume": %s
                }
                """.formatted(permitNo, approvedVolume);
    }

    private String createPermit(String permitNo, String approvedVolume) throws Exception {
        mockMvc.perform(post("/api/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPermitJson(permitNo, approvedVolume)))
                .andExpect(status().isCreated());
        return permitNo;
    }

    private String declarationJson(String eventNo, String occurredDate, String volume) {
        return """
                {"eventNo": "%s", "occurredDate": "%s", "volume": %s}
                """.formatted(eventNo, occurredDate, volume);
    }

    private String reversalJson(String eventNo, String occurredDate, String declarationEventNo) {
        return """
                {"eventNo": "%s", "occurredDate": "%s", "declarationEventNo": "%s"}
                """.formatted(eventNo, occurredDate, declarationEventNo);
    }

    @Test
    void createPermitSucceeds() throws Exception {
        String permitNo = uniquePermitNo();
        mockMvc.perform(post("/api/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPermitJson(permitNo, "1000.500")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.permitNo").value(permitNo))
                .andExpect(jsonPath("$.holder").value("灌区合作社"))
                .andExpect(jsonPath("$.approvedVolume").value(1000.5));

        mockMvc.perform(get("/api/permits/{permitNo}/ledger", permitNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvedVolume").value(1000.5))
                .andExpect(jsonPath("$.effectiveVolume").value(0))
                .andExpect(jsonPath("$.reversedVolume").value(0))
                .andExpect(jsonPath("$.remainingVolume").value(1000.5))
                .andExpect(jsonPath("$.events.length()").value(0));
    }

    @Test
    void createPermitRejectsDuplicatePermitNo() throws Exception {
        String permitNo = createPermit(uniquePermitNo(), "100");
        mockMvc.perform(post("/api/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPermitJson(permitNo, "200")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERMIT_NO_DUPLICATE"));
    }

    @Test
    void createPermitRejectsNonPositiveVolume() throws Exception {
        mockMvc.perform(post("/api/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPermitJson(uniquePermitNo(), "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/api/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPermitJson(uniquePermitNo(), "-5")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void createPermitRejectsInvalidPeriod() throws Exception {
        String body = """
                {
                  "permitNo": "%s",
                  "holder": "灌区合作社",
                  "intakePoint": "东风渠3号闸",
                  "startDate": "2026-09-30",
                  "endDate": "2026-04-01",
                  "approvedVolume": 100
                }
                """.formatted(uniquePermitNo());
        mockMvc.perform(post("/api/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PERMIT_PERIOD_INVALID"));
    }

    @Test
    void declarationUpdatesLedger() throws Exception {
        String permitNo = createPermit(uniquePermitNo(), "1000");
        mockMvc.perform(post("/api/permits/{permitNo}/declarations", permitNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(declarationJson(ev("D-1"), "2026-05-01", "250.5")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventNo").value(ev("D-1")))
                .andExpect(jsonPath("$.type").value("DECLARATION"))
                .andExpect(jsonPath("$.volume").value(250.5));

        mockMvc.perform(get("/api/permits/{permitNo}/ledger", permitNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveVolume").value(250.5))
                .andExpect(jsonPath("$.reversedVolume").value(0))
                .andExpect(jsonPath("$.remainingVolume").value(749.5))
                .andExpect(jsonPath("$.events.length()").value(1));
    }

    @Test
    void declarationOutsidePermitPeriodIsRejected() throws Exception {
        String permitNo = createPermit(uniquePermitNo(), "1000");
        mockMvc.perform(post("/api/permits/{permitNo}/declarations", permitNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(declarationJson(ev("D-early"), "2026-03-31", "10")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EVENT_OUT_OF_PERIOD"));

        mockMvc.perform(post("/api/permits/{permitNo}/declarations", permitNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(declarationJson(ev("D-late"), "2026-10-01", "10")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EVENT_OUT_OF_PERIOD"));
    }

    @Test
    void declarationExceedingQuotaIsRejected() throws Exception {
        String permitNo = createPermit(uniquePermitNo(), "100");
        mockMvc.perform(post("/api/permits/{permitNo}/declarations", permitNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(declarationJson(ev("D-1"), "2026-05-01", "80")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/permits/{permitNo}/declarations", permitNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(declarationJson(ev("D-2"), "2026-05-02", "30")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUOTA_EXCEEDED"));

        mockMvc.perform(get("/api/permits/{permitNo}/ledger", permitNo))
                .andExpect(jsonPath("$.effectiveVolume").value(80))
                .andExpect(jsonPath("$.remainingVolume").value(20))
                .andExpect(jsonPath("$.events.length()").value(1));
    }

    @Test
    void declarationReplayWithSameContentReturnsOriginal() throws Exception {
        String permitNo = createPermit(uniquePermitNo(), "100");
        String body = declarationJson(ev("D-replay"), "2026-05-01", "40");
        String first = mockMvc.perform(post("/api/permits/{permitNo}/declarations", permitNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(post("/api/permits/{permitNo}/declarations", permitNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json(first));

        mockMvc.perform(get("/api/permits/{permitNo}/ledger", permitNo))
                .andExpect(jsonPath("$.effectiveVolume").value(40))
                .andExpect(jsonPath("$.events.length()").value(1));
    }

    @Test
    void declarationReplayWithDifferentContentConflicts() throws Exception {
        String permitNo = createPermit(uniquePermitNo(), "100");
        mockMvc.perform(post("/api/permits/{permitNo}/declarations", permitNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(declarationJson(ev("D-conflict"), "2026-05-01", "40")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/permits/{permitNo}/declarations", permitNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(declarationJson(ev("D-conflict"), "2026-05-01", "50")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_CONFLICT"));
    }

    @Test
    void reversalOffsetsDeclarationAndRestoresQuota() throws Exception {
        String permitNo = createPermit(uniquePermitNo(), "100");
        mockMvc.perform(post("/api/permits/{permitNo}/declarations", permitNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(declarationJson(ev("D-1"), "2026-05-01", "70")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/permits/{permitNo}/reversals", permitNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reversalJson(ev("R-1"), "2026-05-02", ev("D-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("REVERSAL"))
                .andExpect(jsonPath("$.volume").value(70))
                .andExpect(jsonPath("$.declarationEventNo").value(ev("D-1")));

        mockMvc.perform(get("/api/permits/{permitNo}/ledger", permitNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveVolume").value(0))
                .andExpect(jsonPath("$.reversedVolume").value(70))
                .andExpect(jsonPath("$.remainingVolume").value(100))
                .andExpect(jsonPath("$.events.length()").value(2));
    }

    @Test
    void reversalReplayWithSameContentReturnsOriginal() throws Exception {
        String permitNo = createPermit(uniquePermitNo(), "100");
        mockMvc.perform(post("/api/permits/{permitNo}/declarations", permitNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(declarationJson(ev("D-1"), "2026-05-01", "70")))
                .andExpect(status().isOk());
        String body = reversalJson(ev("R-1"), "2026-05-02", ev("D-1"));
        String first = mockMvc.perform(post("/api/permits/{permitNo}/reversals", permitNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(post("/api/permits/{permitNo}/reversals", permitNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json(first));

        mockMvc.perform(get("/api/permits/{permitNo}/ledger", permitNo))
                .andExpect(jsonPath("$.events.length()").value(2));
    }

    @Test
    void secondReversalOfSameDeclarationConflicts() throws Exception {
        String permitNo = createPermit(uniquePermitNo(), "100");
        mockMvc.perform(post("/api/permits/{permitNo}/declarations", permitNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(declarationJson(ev("D-1"), "2026-05-01", "70")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/permits/{permitNo}/reversals", permitNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reversalJson(ev("R-1"), "2026-05-02", ev("D-1"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/permits/{permitNo}/reversals", permitNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reversalJson(ev("R-2"), "2026-05-03", ev("D-1"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_REVERSED"));
    }

    @Test
    void reversalOfReversalIsRejected() throws Exception {
        String permitNo = createPermit(uniquePermitNo(), "100");
        mockMvc.perform(post("/api/permits/{permitNo}/declarations", permitNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(declarationJson(ev("D-1"), "2026-05-01", "70")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/permits/{permitNo}/reversals", permitNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reversalJson(ev("R-1"), "2026-05-02", ev("D-1"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/permits/{permitNo}/reversals", permitNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reversalJson(ev("R-2"), "2026-05-03", ev("R-1"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVERSAL_NOT_ALLOWED"));
    }

    @Test
    void reversalOfUnknownDeclarationIsNotFound() throws Exception {
        String permitNo = createPermit(uniquePermitNo(), "100");
        mockMvc.perform(post("/api/permits/{permitNo}/reversals", permitNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reversalJson(ev("R-1"), "2026-05-02", ev("D-missing"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DECLARATION_NOT_FOUND"));
    }

    @Test
    void operationsOnUnknownPermitAreNotFound() throws Exception {
        mockMvc.perform(get("/api/permits/{permitNo}/ledger", "P-missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PERMIT_NOT_FOUND"));
        mockMvc.perform(post("/api/permits/{permitNo}/declarations", "P-missing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(declarationJson(ev("D-1"), "2026-05-01", "10")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PERMIT_NOT_FOUND"));
    }
}
