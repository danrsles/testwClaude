package com.example.demo.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OriginSecretFilterTest {

    private MockHttpServletResponse run(OriginSecretFilter filter, String header, MockFilterChain chain)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/wants");
        if (header != null) {
            request.addHeader(OriginSecretFilter.HEADER, header);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void passesRequestsWithTheRightSecret() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response = run(new OriginSecretFilter(true, "s3cret"), "s3cret", chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsRequestsWithoutTheHeader() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response = run(new OriginSecretFilter(true, "s3cret"), null, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void rejectsRequestsWithTheWrongSecret() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response = run(new OriginSecretFilter(true, "s3cret"), "guess", chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void refusesToStartEnabledWithABlankSecret() {
        assertThatThrownBy(() -> new OriginSecretFilter(true, " "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ORIGIN_SECRET");
    }

    @Test
    void letsEverythingThroughWhenDisabled() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response = run(new OriginSecretFilter(false, ""), null, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
