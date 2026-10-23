package com.tickit.service;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntSupplier;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Bounded component contention checks. Never creates customer events, holds or bookings. */
@Service
public class PipelineDiagnostics {
  private final DataSource database;
  private final StringRedisTemplate redis;
  private final ExecutorService coordinator = Executors.newSingleThreadExecutor();
  private volatile Map<String, Object> latest = Map.of("status", "IDLE");
  private boolean running;
  private long lastStarted;

  public PipelineDiagnostics(DataSource database, StringRedisTemplate redis) {
    this.database = database;
    this.redis = redis;
  }

  public Map<String, Object> status() {
    return latest;
  }

  public synchronized Map<String, Object> start(int attempts) {
    if (attempts < 8 || attempts > 32)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose 8–32 attempts");
    if (running || System.currentTimeMillis() - lastStarted < 60000)
      throw new ResponseStatusException(
          HttpStatus.TOO_MANY_REQUESTS, "Wait one minute between tests");
    running = true;
    lastStarted = System.currentTimeMillis();
    UUID id = UUID.randomUUID();
    latest =
        Map.of("id", id, "status", "RUNNING", "startedAt", Instant.now(), "attempts", attempts);
    coordinator.submit(() -> run(id, attempts));
    return latest;
  }

  private void run(UUID id, int attempts) {
    String prefix = "diagnostics:" + id;
    List<String> keys = List.of(prefix + ":hold", prefix + ":expiry", prefix + ":bucket");
    List<Map<String, Object>> checks = new ArrayList<>();
    ExecutorService bots = Executors.newFixedThreadPool(4);
    long start = System.nanoTime();
    boolean clean = false;
    String failure = null;
    try {
      // Clean stale diagnostics only, including records from an interrupted earlier run.
      sql("DELETE FROM diagnostic_claim WHERE created_at < now() - interval '1 day'", null);
      checks.add(
          check(
              "Redis seat contention",
              attempts,
              bots,
              () -> {
                var result =
                    redis.execute(
                        script("acquire_hold.lua"),
                        keys.subList(0, 2),
                        UUID.randomUUID().toString(),
                        "30");
                redis.expire(keys.get(1), java.time.Duration.ofSeconds(60));
                if (result == null) throw new IllegalStateException("No Redis result");
                return result.intValue();
              },
              true));
      checks.add(
          check(
              "Database unique-seat constraint",
              attempts,
              bots,
              () ->
                  sql(
                      "INSERT INTO diagnostic_claim(run_id,seat) VALUES (?,1) ON CONFLICT DO"
                          + " NOTHING",
                      id),
              true));
      checks.add(
          check(
              "Database optimistic version conflict",
              attempts,
              bots,
              () ->
                  sql(
                      "UPDATE diagnostic_claim SET version=version+1 WHERE run_id=? AND seat=1 AND"
                          + " version=0",
                      id),
              true));
      checks.add(
          check(
              "Atomic token bucket",
              attempts,
              bots,
              () -> {
                var result =
                    redis.execute(script("rate_limiter.lua"), List.of(keys.get(2)), "4", "0");
                if (result == null) throw new IllegalStateException("No Redis result");
                return result.intValue();
              },
              false));
    } catch (Exception e) {
      failure = e.getClass().getSimpleName();
    } finally {
      bots.shutdownNow();
      try {
        if (bots.awaitTermination(10, TimeUnit.SECONDS)) {
          sql("DELETE FROM diagnostic_claim WHERE run_id=?", id);
          redis.delete(keys);
          clean = true;
        }
      } catch (Exception e) {
        failure = "Cleanup failed: " + e.getClass().getSimpleName();
      }
      var result = new LinkedHashMap<String, Object>();
      result.put("id", id);
      result.put(
          "status",
          failure == null
                  && clean
                  && checks.size() == 4
                  && checks.stream().allMatch(c -> Boolean.TRUE.equals(c.get("passed")))
              ? "PASSED"
              : "FAILED");
      result.put("attempts", attempts);
      result.put("concurrency", 4);
      result.put("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
      result.put("finishedAt", Instant.now());
      result.put("checks", checks);
      result.put("cleanupComplete", clean);
      if (failure != null) result.put("error", failure);
      latest = Collections.unmodifiableMap(result);
      synchronized (this) {
        running = false;
      }
    }
  }

  private Map<String, Object> check(
      String name, int attempts, ExecutorService bots, IntSupplier operation, boolean singleWinner)
      throws InterruptedException {
    List<Callable<long[]>> tasks = new ArrayList<>();
    for (int i = 0; i < attempts; i++)
      tasks.add(
          () -> {
            long start = System.nanoTime();
            try {
              return new long[] {operation.getAsInt(), System.nanoTime() - start};
            } catch (Exception e) {
              return new long[] {-1, System.nanoTime() - start};
            }
          });
    int accepted = 0, rejected = 0, errors = 0;
    List<Long> times = new ArrayList<>();
    for (Future<long[]> future : bots.invokeAll(tasks, 15, TimeUnit.SECONDS)) {
      try {
        long[] r = future.get();
        if (r[0] == 1) accepted++;
        else if (r[0] == 0) rejected++;
        else errors++;
        times.add(r[1] / 1000000);
      } catch (Exception e) {
        errors++;
      }
    }
    Collections.sort(times);
    long p95 = times.isEmpty() ? 0 : times.get((int) Math.ceil(times.size() * .95) - 1);
    boolean passed =
        errors == 0 && accepted == (singleWinner ? 1 : 4) && rejected == attempts - accepted;
    return Map.of(
        "name",
        name,
        "passed",
        passed,
        "accepted",
        accepted,
        "rejected",
        rejected,
        "errors",
        errors,
        "p95Ms",
        p95);
  }

  private int sql(String sql, UUID id) {
    try (var connection = database.getConnection();
        var statement = connection.prepareStatement(sql)) {
      statement.setQueryTimeout(3);
      if (id != null) statement.setObject(1, id);
      return statement.executeUpdate();
    } catch (Exception e) {
      throw new IllegalStateException("Database diagnostic failed", e);
    }
  }

  private DefaultRedisScript<Long> script(String name) {
    var script = new DefaultRedisScript<Long>();
    script.setLocation(new ClassPathResource("scripts/" + name));
    script.setResultType(Long.class);
    return script;
  }

  @PreDestroy
  void close() {
    coordinator.shutdownNow();
  }
}
