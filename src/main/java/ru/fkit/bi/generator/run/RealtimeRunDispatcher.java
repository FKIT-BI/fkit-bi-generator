package ru.fkit.bi.generator.run;

import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Claims only when a worker slot is available; thread and in-flight batch memory are bounded.
 * An endless realtime run holds its slot until it stops, so the pool size caps concurrently live runs.
 */
@Component
public class RealtimeRunDispatcher {
  private final RealtimeRunLifecycleRepository repository;
  private final RealtimeRunExecutor executor;
  private final String workerId = UUID.randomUUID().toString();
  private final ThreadPoolExecutor workers;

  public RealtimeRunDispatcher(RealtimeRunLifecycleRepository repository, RealtimeRunExecutor executor,
      @Value("${generator.realtime.workers:8}") int workerCount) {
    if (workerCount < 1) throw new IllegalArgumentException("generator.realtime.workers must be positive");
    this.repository = repository;
    this.executor = executor;
    this.workers = new ThreadPoolExecutor(workerCount, workerCount, 0L, TimeUnit.MILLISECONDS,
        new SynchronousQueue<>(), runnable -> {
          Thread thread = new Thread(runnable, "generator-realtime-run");
          thread.setDaemon(true);
          return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
  }

  int workerCount() { return workers.getMaximumPoolSize(); }

  @Scheduled(fixedDelayString = "${generator.realtime.dispatch-delay-ms:250}", initialDelayString = "${generator.realtime.initial-delay-ms:0}")
  public void dispatch() {
    if (workers.getActiveCount() >= workers.getMaximumPoolSize()) return;
    repository.claim(workerId, repository.databaseNow()).ifPresent(claim -> {
      try { workers.execute(() -> executor.execute(claim)); }
      catch (RuntimeException rejected) { /* lease expiry makes this claim recoverable */ }
    });
  }

  @PreDestroy
  public void stop() {
    workers.shutdown();
    try {
      if (!workers.awaitTermination(8, TimeUnit.SECONDS)) workers.shutdownNow();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      workers.shutdownNow();
    }
  }
}
