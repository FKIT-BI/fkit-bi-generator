package ru.fkit.bi.generator.run;

import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** One bounded historical worker, matching the minimum-host historical capacity slot. */
@Component
public class HistoricalRunDispatcher {
  private final RealtimeRunLifecycleRepository repository;
  private final HistoricalRunExecutor executor;
  private final String workerId=UUID.randomUUID().toString();
  private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
      new ArrayBlockingQueue<>(1),r->{Thread t=new Thread(r,"generator-historical-run");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
  public HistoricalRunDispatcher(RealtimeRunLifecycleRepository repository,HistoricalRunExecutor executor){this.repository=repository;this.executor=executor;}

  @Scheduled(fixedDelayString="${generator.historical.dispatch-delay-ms:500}",initialDelayString="${generator.historical.initial-delay-ms:0}")
  public void dispatch(){
    if(worker.getActiveCount()>0||!worker.getQueue().isEmpty())return;
    repository.claimHistorical(workerId,repository.databaseNow()).ifPresent(claim->{try{worker.execute(()->executor.execute(claim));}catch(RuntimeException ignored){/* Durable lease expires and makes it recoverable. */}});
  }
  @PreDestroy public void stop(){worker.shutdown();try{if(!worker.awaitTermination(8,TimeUnit.SECONDS))worker.shutdownNow();}catch(InterruptedException e){Thread.currentThread().interrupt();worker.shutdownNow();}}
}
