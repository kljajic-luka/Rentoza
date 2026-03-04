package org.example.rentoza.security.csrf;

import org.example.rentoza.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for CustomCookieCsrfTokenRepository.
 * Verifies CSRF cookie lifecycle behavior.
 */
class CustomCookieCsrfTokenRepositoryTest {

    private CustomCookieCsrfTokenRepository repository;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        AppProperties appProperties = new AppProperties();
        appProperties.getCookie().setDomain("localhost");
        appProperties.getCookie().setSecure(false);
        appProperties.getCookie().setSameSite("Lax");

        repository = new CustomCookieCsrfTokenRepository(appProperties);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    @Test
    @DisplayName("saveToken sets Max-Age=1209600 (14 days) on CSRF cookie")
    void saveToken_setsMaxAge14Days() {
        CsrfToken token = repository.generateToken(request);

        repository.saveToken(token, request, response);

        String setCookieHeader = response.getHeader("Set-Cookie");
        assertThat(setCookieHeader).isNotNull();
        assertThat(setCookieHeader).contains("XSRF-TOKEN=");
        assertThat(setCookieHeader).contains("Max-Age=1209600");
    }

    @Test
    @DisplayName("saveToken(null) clears cookie with Max-Age=0")
    void saveToken_null_clearsCookie() {
        repository.saveToken(null, request, response);

        String setCookieHeader = response.getHeader("Set-Cookie");
        assertThat(setCookieHeader).isNotNull();
        assertThat(setCookieHeader).contains("Max-Age=0");
    }

    @Test
    @DisplayName("generateToken produces non-null token with correct header/param names")
    void generateToken_producesValidToken() {
        CsrfToken token = repository.generateToken(request);

        assertThat(token).isNotNull();
        assertThat(token.getToken()).isNotEmpty();
        assertThat(token.getHeaderName()).isEqualTo("X-XSRF-TOKEN");
        assertThat(token.getParameterName()).isEqualTo("_csrf");
    }

    @Test
    @DisplayName("loadToken returns null when no cookie set")
    void loadToken_noCookie_returnsNull() {
        CsrfToken loaded = repository.loadToken(request);
        assertThat(loaded).isNull();
    }
}
