package com.menora.initializr;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that {@link com.menora.initializr.admin.GlobalExceptionHandler} applies to
 * <em>all</em> controllers (not just {@code /admin/*}): unexpected failures return a
 * structured {@code {error, detail}} 500 instead of Spring's whitelabel page, and the
 * cause of a wrapped {@link UncheckedIOException} is surfaced. A non-admin
 * {@link IllegalArgumentException} now yields a structured 400.
 *
 * <p>{@link BoomController} is a test-only controller imported solely for this class.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(GlobalErrorHandlingTests.BoomController.class)
class GlobalErrorHandlingTests {

    @Autowired
    private MockMvc mvc;

    @Test
    void uncheckedIoExceptionReturnsStructured500WithCauseMessage() throws Exception {
        mvc.perform(get("/test-boom/io"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Internal error"))
                .andExpect(jsonPath("$.detail").value("disk gone"));
    }

    @Test
    void illegalArgumentOnNonAdminControllerReturnsStructured400() throws Exception {
        mvc.perform(get("/test-boom/illegal"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"))
                .andExpect(jsonPath("$.detail").value("bad arg"));
    }

    @RestController
    static class BoomController {

        @GetMapping("/test-boom/io")
        String io() {
            throw new UncheckedIOException(new IOException("disk gone"));
        }

        @GetMapping("/test-boom/illegal")
        String illegal() {
            throw new IllegalArgumentException("bad arg");
        }
    }
}
