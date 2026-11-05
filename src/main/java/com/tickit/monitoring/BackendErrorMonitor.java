package com.tickit.monitoring;

import io.sentry.*;
import io.sentry.protocol.*;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Explicit error metadata only. No request, customer, exception message or global SDK scope. */
@Component
public class BackendErrorMonitor {
  public enum Kind {
    HTTP_500,
    DEPENDENCY_503
  }

  private final ISentryClient client;
  private final long intervalNanos;
  private final LongSupplier clock;
  private boolean reported;
  private long lastReport;
  private final AtomicLong submitted = new AtomicLong();
  private final AtomicLong suppressed = new AtomicLong();
  private final AtomicLong failures = new AtomicLong();

  @Autowired
  public BackendErrorMonitor(
      @Value("${app.monitoring.enabled:false}") boolean enabled,
      @Value("${app.monitoring.dsn:}") String dsn,
      @Value("${app.monitoring.environment:production}") String environment,
      @Value("${app.monitoring.release:}") String release,
      @Value("${app.monitoring.min-interval-seconds:300}") long intervalSeconds) {
    if (intervalSeconds < 60 || intervalSeconds > 3600)
      throw new IllegalArgumentException("Monitoring interval must be between 60 and 3600 seconds");
    client = enabled ? createClient(dsn, environment, release) : null;
    intervalNanos = Duration.ofSeconds(intervalSeconds).toNanos();
    clock = System::nanoTime;
  }

  BackendErrorMonitor(ISentryClient client, Duration interval, LongSupplier clock) {
    this.client = client;
    this.intervalNanos = interval.toNanos();
    this.clock = clock;
  }

  private static ISentryClient createClient(String dsn, String environment, String release) {
    var options = options(dsn, environment, release);
    try {
      return new SentryClient(options);
    } catch (RuntimeException ex) {
      throw new IllegalArgumentException(
          "Unable to initialize monitoring; verify the private DSN configuration");
    }
  }

  static SentryOptions options(String dsn, String environment, String release) {
    // Validate privately without ever including the configured URL in errors.
    try {
      URI uri = URI.create(dsn);
      if (!"https".equals(uri.getScheme())
          || uri.getHost() == null
          || uri.getUserInfo() == null
          || uri.getQuery() != null
          || uri.getFragment() != null) throw new IllegalArgumentException();
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Enabled monitoring requires a valid HTTPS Sentry DSN");
    }
    if (!environment.matches("[A-Za-z0-9._-]{1,40}")
        || (!release.isBlank() && !release.matches("[A-Za-z0-9._-]{1,80}")))
      throw new IllegalArgumentException("Invalid monitoring environment or release identifier");
    var options = new SentryOptions();
    options.setDsn(dsn);
    options.setEnvironment(environment);
    if (!release.isBlank()) options.setRelease(release);
    options.getIntegrations().clear();
    options.setEnableExternalConfiguration(false);
    options.setAttachThreads(false);
    options.setAttachStacktrace(false);
    options.setAttachServerName(false);
    options.setSendModules(false);
    options.setMaxBreadcrumbs(0);
    options.setMaxRequestBodySize(SentryOptions.RequestSize.NONE);
    options.setSendDefaultPii(false);
    options.setTracesSampleRate(0d);
    options.setProfilesSampleRate(0d);
    options.getLogs().setEnabled(false);
    options.getMetrics().setEnabled(false);
    options.setSendClientReports(false);
    options.setMaxQueueSize(8);
    options.setMaxAttachmentSize(0);
    options.setConnectionTimeoutMillis(2000);
    options.setReadTimeoutMillis(2000);
    options.setShutdownTimeoutMillis(1000);
    // Rebuild an allowlisted event AFTER SDK processors. No enriched request/user/runtime data.
    options.setBeforeSend(
        (event, hint) -> {
          var safe = new SentryEvent(event.getTimestamp());
          safe.setEventId(event.getEventId());
          safe.setPlatform("java");
          safe.setLevel(event.getLevel());
          safe.setEnvironment(environment);
          if (!release.isBlank()) safe.setRelease(release);
          safe.setExceptions(event.getExceptions());
          safe.setTag("component", "tickit-backend");
          String kind = event.getTag("kind");
          if (kind != null && Arrays.stream(Kind.values()).anyMatch(k -> k.name().equals(kind)))
            safe.setTag("kind", kind);
          return safe;
        });
    return options;
  }

  /** Best effort; monitoring must not change the booking API's response on provider failure. */
  public void report(Throwable error, Kind kind) {
    if (client == null || error == null) return;
    if (!permit()) {
      suppressed.incrementAndGet();
      return;
    }
    try {
      var exception = new SentryException();
      exception.setType(error.getClass().getSimpleName());
      exception.setModule(error.getClass().getPackageName());
      exception.setValue("Server failure; exception message intentionally omitted");
      var frames = new ArrayList<SentryStackFrame>();
      for (var source : error.getStackTrace()) {
        if (!source.getClassName().startsWith("com.tickit.") || frames.size() == 40) continue;
        var frame = new SentryStackFrame();
        frame.setModule(source.getClassName());
        frame.setFunction(source.getMethodName());
        if (source.getFileName() != null && source.getFileName().matches("[A-Za-z0-9_.-]{1,100}"))
          frame.setFilename(source.getFileName());
        if (source.getLineNumber() >= 0) frame.setLineno(source.getLineNumber());
        frame.setInApp(true);
        frames.add(frame);
      }
      Collections.reverse(frames);
      exception.setStacktrace(new SentryStackTrace(frames));
      var event = new SentryEvent();
      event.setLevel(SentryLevel.ERROR);
      event.setExceptions(List.of(exception));
      event.setTag("kind", kind.name());
      var id = client.captureEvent(event, null, new Hint());
      if (id != null && !SentryId.EMPTY_ID.equals(id)) submitted.incrementAndGet();
      else failures.incrementAndGet();
    } catch (RuntimeException ex) {
      // Never forward provider errors or the original exception's potentially sensitive message.
      failures.incrementAndGet();
    }
  }

  private synchronized boolean permit() {
    long now = clock.getAsLong();
    if (reported && now - lastReport < intervalNanos) return false;
    reported = true;
    lastReport = now;
    return true;
  }

  public Map<String, Object> status() {
    return Map.of(
        "state",
        client == null ? "DISABLED" : "CONFIGURED_REQUIRES_ACCEPTANCE",
        "minimumIntervalSeconds",
        Duration.ofNanos(intervalNanos).toSeconds(),
        "submittedToSdk",
        submitted.get(),
        "suppressed",
        suppressed.get(),
        "submissionFailures",
        failures.get());
  }

  @PreDestroy
  public void close() {
    if (client != null) {
      try {
        client.close();
      } catch (RuntimeException ignored) {
        /* best effort shutdown */
      }
    }
  }
}
