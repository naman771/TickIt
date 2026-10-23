package com.tickit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.tickit.service.PipelineDiagnostics;
import java.sql.SQLException;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

class DiagnosticsFailureTest {
  @Test
  void databaseFailureIsReportedWithoutPretendingChecksPassed() throws Exception {
    var database = mock(DataSource.class);
    var redis = mock(StringRedisTemplate.class);
    when(database.getConnection()).thenThrow(new SQLException("Provider unavailable"));
    var diagnostics = new PipelineDiagnostics(database, redis);
    try {
      diagnostics.start(8);
      assertTimeoutPreemptively(
          Duration.ofSeconds(5),
          () -> {
            while (diagnostics.status().get("status").equals("RUNNING")) Thread.sleep(10);
          });
      assertEquals("FAILED", diagnostics.status().get("status"));
      assertEquals(false, diagnostics.status().get("cleanupComplete"));
      assertFalse(diagnostics.status().toString().contains("Provider unavailable"));
      verifyNoInteractions(redis);
    } finally {
      var close = PipelineDiagnostics.class.getDeclaredMethod("close");
      close.setAccessible(true);
      close.invoke(diagnostics);
    }
  }
}
