package ru.fkit.bi.generator.run;

import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Claims only when a worker slot is available; thread and in-flight batch memory are bounded. */
@Component
public class RealtimeRunDispatcher {
  private final RealtimeRunLifecycleRepository repository;
  private final RealtimeRunExecutor executor;
  private final String workerId = UUID.randomUUID().toString();
  private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS,
      new SynchronousQueue<>(), runnable -> {
        Thread thread = new Thread(runnable, "generator-realtime-run");
        thread.setDaemon(true);
        return thread;
      }, new ThreadPoolExecutor.AbortPolicy());

  public RealtimeRunDispatcher(RealtimeRunLifecycleRepository repository, RealtimeRunExecutor executor) {
    this.repository = repository;
    this.executor = executor;
  }

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
