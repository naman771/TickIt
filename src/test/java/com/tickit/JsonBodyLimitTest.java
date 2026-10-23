package com.tickit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.tickit.filter.JsonBodyLimitFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class JsonBodyLimitTest {
  @Test
  void chunkedOversizePayloadIsRejectedWithoutTrustingContentLength() throws Exception {
    var request =
        new MockHttpServletRequest() {
          @Override
          public long getContentLengthLong() {
            return -1;
          }
        };
    request.setRequestURI("/api/holds");
    request.setContentType("application/json");
    request.setContent(new byte[JsonBodyLimitFilter.MAX_BYTES + 1]);
    var response = new MockHttpServletResponse();
    var chain = mock(FilterChain.class);
    new JsonBodyLimitFilter().doFilter(request, response, chain);
    assertEquals(413, response.getStatus());
    verifyNoInteractions(chain);
  }

  @Test
  void maximumSizedValidJsonReachesTheHandlerUnchanged() throws Exception {
    String overhead = "{\"pad\":\"\"}";
    byte[] body =
        ("{\"pad\":\"" + "x".repeat(JsonBodyLimitFilter.MAX_BYTES - overhead.length()) + "\"}")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    assertEquals(JsonBodyLimitFilter.MAX_BYTES, body.length);
    var request = new MockHttpServletRequest();
    request.setRequestURI("/api/holds");
    request.setContentType("application/json");
    request.setContent(body);
    var response = new MockHttpServletResponse();
    var seen = new java.util.concurrent.atomic.AtomicBoolean();
    new JsonBodyLimitFilter()
        .doFilter(
            request,
            response,
            (wrapped, res) -> {
              assertArrayEquals(body, wrapped.getInputStream().readAllBytes());
              seen.set(true);
            });
    assertTrue(seen.get());
  }
}
