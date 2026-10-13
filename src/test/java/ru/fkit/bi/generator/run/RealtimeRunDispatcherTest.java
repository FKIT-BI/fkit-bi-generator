package ru.fkit.bi.generator.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RealtimeRunDispatcherTest {
  @Test
  void workerPoolSizeCapsConcurrentRealtimeRuns() {
    RealtimeRunDispatcher dispatcher = new RealtimeRunDispatcher(null, null, 6);
    try {
      assertEquals(6, dispatcher.workerCount());
    } finally {
      dispatcher.stop();
    }
    assertThrows(IllegalArgumentException.class, () -> new RealtimeRunDispatcher(null, null, 0));
  }
}
