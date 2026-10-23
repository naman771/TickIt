package com.tickit.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bound JSON before deserialization; DTO size validation alone happens too late to protect heap.
 */
@Component
@Order(-90) // After Spring Security; multipart uploads have their own servlet limits.
public class JsonBodyLimitFilter extends OncePerRequestFilter {
  public static final int MAX_BYTES = 1024 * 1024;

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String type = request.getContentType();
    type = type == null ? "" : type.toLowerCase(Locale.ROOT).split(";")[0].trim();
    return !request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/")
        || !(type.equals("application/json") || type.endsWith("+json"));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (request.getContentLengthLong() > MAX_BYTES) {
      tooLarge(response);
      return;
    }
    byte[] body = request.getInputStream().readNBytes(MAX_BYTES + 1);
    if (body.length > MAX_BYTES) {
      tooLarge(response);
      return;
    }
    chain.doFilter(
        new HttpServletRequestWrapper(request) {
          @Override
          public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
              @Override
              public int read() {
                return input.read();
              }

              @Override
              public int read(byte[] bytes, int offset, int length) {
                return input.read(bytes, offset, length);
              }

              @Override
              public boolean isFinished() {
                return input.available() == 0;
              }

              @Override
              public boolean isReady() {
                return true;
              }

              @Override
              public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("Synchronous JSON requests only");
              }
            };
          }

          @Override
          public BufferedReader getReader() {
            return new BufferedReader(
                new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
          }
        },
        response);
  }

  private void tooLarge(HttpServletResponse response) throws IOException {
    response.setStatus(413);
    response.setContentType("application/json");
    response
        .getWriter()
        .write("{\"status\":413,\"error\":\"JSON request exceeds the 1 MB limit\"}");
  }
}
