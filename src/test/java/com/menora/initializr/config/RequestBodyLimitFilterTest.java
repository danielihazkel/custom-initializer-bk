package com.menora.initializr.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RequestBodyLimitFilter}: a body past the limit is a 413 that nothing else sees; one
 * within it goes on untouched — read here first only when it does not say how long it is.
 */
class RequestBodyLimitFilterTest {

    private final RequestBodyLimitFilter filter = new RequestBodyLimitFilter(DataSize.ofKilobytes(1), DataSize.ofKilobytes(4));

    /** A request that does not say how long its body is, as a chunked one does not. */
    private static MockHttpServletRequest chunked(String method, String path, byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path) {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(body);
        request.addHeader("Transfer-Encoding", "chunked");
        return request;
    }

    private static MockHttpServletRequest sized(String method, String path, byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setContent(body);
        return request;
    }

    private record Outcome(MockHttpServletResponse response, MockFilterChain chain) {
        boolean wentOn() {
            return chain.getRequest() != null;
        }
    }

    private Outcome run(RequestBodyLimitFilter through, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        through.doFilter(request, response, chain);
        return new Outcome(response, chain);
    }

    private static byte[] bytes(int count) {
        return "x".repeat(count).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aBodyThatSaysItIsTooLongIsRefusedBeforeAnythingReadsIt() throws Exception {
        Outcome refused = run(filter, sized("POST", "/starter-wizard.zip", bytes(1025)));

        assertThat(refused.wentOn()).isFalse();
        assertThat(refused.response().getStatus()).isEqualTo(413);
        assertThat(refused.response().getContentType()).startsWith("application/json");
        assertThat(refused.response().getHeader("Connection")).isEqualTo("close");
        assertThat(refused.response().getContentAsString())
                .isEqualTo("{\"error\":\"Request too large\",\"detail\":\"The request body is 0.0 MB; "
                        + "this server takes 0.0 MB (menora.request.max-body-size)\"}");

        // At the limit it goes on, as it came.
        MockHttpServletRequest atTheLimit = sized("POST", "/starter-wizard.zip", bytes(1024));
        Outcome allowed = run(filter, atTheLimit);
        assertThat(allowed.wentOn()).isTrue();
        assertThat(allowed.chain().getRequest()).isSameAs(atTheLimit);
        assertThat(allowed.response().getStatus()).isEqualTo(200);
    }

    @Test
    void aBodyThatDoesNotSayHowLongItIsIsReadToTheLimit() throws Exception {
        // Within the limit: read here, and handed on with every byte and its length.
        byte[] body = "{\"name\":\"שלום\"}".getBytes(StandardCharsets.UTF_8);
        Outcome allowed = run(filter, chunked("PUT", "/metadata/fullstack/models/1", body));
        assertThat(allowed.wentOn()).isTrue();
        assertThat(allowed.chain().getRequest().getInputStream().readAllBytes()).isEqualTo(body);
        assertThat(allowed.chain().getRequest().getContentLengthLong()).isEqualTo(body.length);
        Outcome read = run(filter, chunked("POST", "/x", body));
        assertThat(read.chain().getRequest().getReader().readLine()).isEqualTo("{\"name\":\"שלום\"}");

        // Past it: refused as soon as it passes — whatever it would have gone on to say.
        Outcome refused = run(filter, chunked("POST", "/starter-wizard.zip", bytes(5000)));
        assertThat(refused.wentOn()).isFalse();
        assertThat(refused.response().getStatus()).isEqualTo(413);
        assertThat(refused.response().getContentAsString()).contains("more than 0.0 MB").contains("menora.request.max-body-size");

        // No body at all is no body.
        assertThat(run(filter, chunked("POST", "/admin/refresh", new byte[0])).wentOn()).isTrue();
    }

    @Test
    void theAdminImportHasItsOwnLargerLimit() throws Exception {
        assertThat(run(filter, sized("POST", "/admin/import", bytes(4096))).wentOn()).isTrue();
        assertThat(run(filter, chunked("POST", "/admin/import", bytes(4096))).wentOn()).isTrue();

        Outcome refused = run(filter, sized("POST", "/admin/import", bytes(4097)));
        assertThat(refused.response().getStatus()).isEqualTo(413);
        assertThat(refused.response().getContentAsString()).contains("menora.request.max-import-size");
        // Only that path: anything else under /admin is an ordinary request.
        assertThat(run(filter, sized("POST", "/admin/import/x", bytes(2000))).response().getStatus()).isEqualTo(413);
        assertThat(run(filter, sized("PUT", "/admin/dependency-entries/3", bytes(2000))).response().getStatus()).isEqualTo(413);
        // Under a context path too.
        MockHttpServletRequest deployed = sized("POST", "/initializr/admin/import", bytes(4096));
        deployed.setContextPath("/initializr");
        assertThat(run(filter, deployed).wentOn()).isTrue();
    }

    @Test
    void whatCarriesNoBodyAndWhatTheContainerReadsItselfAreLeftAlone() throws Exception {
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/starter.zip");
        Outcome plain = run(filter, get);
        assertThat(plain.wentOn()).isTrue();
        assertThat(plain.chain().getRequest()).isSameAs(get);

        // A form's fields are the container's to read, under its own limit: not read here.
        MockHttpServletRequest form = chunked("POST", "/starter.zip", "dependencies=web".getBytes(StandardCharsets.UTF_8));
        form.setContentType("application/x-www-form-urlencoded; charset=UTF-8");
        assertThat(run(filter, form).chain().getRequest()).isSameAs(form);
        // …but one that says it is too long is refused like any body.
        MockHttpServletRequest longForm = sized("POST", "/starter.zip", bytes(2000));
        longForm.setContentType("application/x-www-form-urlencoded");
        assertThat(run(filter, longForm).response().getStatus()).isEqualTo(413);
    }

    @Test
    void zeroSwitchesALimitOff() throws Exception {
        RequestBodyLimitFilter off = new RequestBodyLimitFilter(DataSize.ofBytes(0), DataSize.ofBytes(0));
        assertThat(run(off, sized("POST", "/starter-wizard.zip", bytes(50_000))).wentOn()).isTrue();
        assertThat(run(off, sized("POST", "/admin/import", bytes(50_000))).wentOn()).isTrue();
    }
}
