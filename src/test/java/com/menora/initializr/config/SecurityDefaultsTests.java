package com.menora.initializr.config;

import com.menora.initializr.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the application answers as it is shipped — the configuration every other test class runs
 * on — for two of Round 14's security follow-ups: a request body larger than the server takes, and
 * a database typed in by hand. The rules themselves are pinned without a context
 * ({@code RequestBodyLimitFilterTest}, {@code LiveSchemaServiceTest}); this is that they are in force.
 */
@IntegrationTest
class SecurityDefaultsTests {

    @Autowired private MockMvc mvc;

    @Test
    void aBodyLargerThanTheServerTakesIsRefusedWhateverItIsFor() throws Exception {
        // One megabyte past the shipped 32 MB. Nothing parses it: the filter is first in the chain.
        byte[] huge = new byte[33 * 1024 * 1024];
        java.util.Arrays.fill(huge, (byte) ' ');
        for (String path : new String[] {"/starter-wizard.zip", "/starter-fullstack.zip", "/metadata/fullstack/models",
                "/admin/dependency-entries", "/no-such-endpoint"}) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(huge))
                    .andExpect(status().isPayloadTooLarge())
                    .andExpect(jsonPath("$.error").value("Request too large"))
                    .andExpect(jsonPath("$.detail").value("The request body is 33.0 MB; this server takes 32.0 MB "
                            + "(menora.request.max-body-size)"));
        }
        // The admin configuration import takes more: this one gets as far as the admin login.
        mvc.perform(post("/admin/import").contentType(MediaType.APPLICATION_JSON).content(huge))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aDatabaseTypedInByHandIsRefusedUnlessTheServerWasToldToAllowIt() throws Exception {
        mvc.perform(get("/metadata/live-schema/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.typedConnections").value(false));
        // The server does not connect: no host is looked up, nothing is opened.
        mvc.perform(post("/metadata/live-schema/schemas").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"connection\":{\"dialect\":\"POSTGRESQL\",\"host\":\"10.20.30.40\",\"port\":5432,"
                                + "\"database\":\"hr\",\"user\":\"reader\",\"password\":\"x\"}}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Connections typed in by hand are turned off on this server. "
                        + "Ask an admin to add the database as a source."));
    }
}
