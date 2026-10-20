package com.gic.explanation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ExplanationController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class ExplanationControllerTest {
    @Autowired MockMvc mvc;
    @MockBean ExplanationService explanations;
    private final String id = ExplanationFixtures.get("capacity_rejection").tradeId();
    @Test void defaultEndpointIsNoStoreAndUsesRules() throws Exception {
        var snapshot = ExplanationFixtures.get("capacity_rejection");
        var result = new ExplanationService(ignored -> snapshot, new ExplanationEngine(new RuleCatalog()), ignored -> { throw new AssertionError(); }).explain(id, false);
        when(explanations.explain(id, false)).thenReturn(result);
        mvc.perform(get("/api/trader/trades/" + id + "/explanation"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.mode").value("rules")).andExpect(jsonPath("$.modelCalls").value(0));
        verify(explanations).explain(id, false);
    }
    @Test void explicitAiModeUsesTheSameReadOnlyService() throws Exception {
        mvc.perform(get("/api/trader/trades/" + id + "/explanation?mode=ai")).andExpect(status().isOk());
        verify(explanations).explain(id, true);
    }
    @Test void arbitraryModeDoesNotReachTheService() throws Exception {
        mvc.perform(get("/api/trader/trades/" + id + "/explanation?mode=execute")).andExpect(status().isBadRequest());
        verifyNoInteractions(explanations);
    }
    @Test void thereIsNoExplanationMutationEndpoint() throws Exception {
        mvc.perform(post("/api/trader/trades/" + id + "/explanation")).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(explanations);
    }
}
