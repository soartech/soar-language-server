package com.soartech.soarls.util;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The debouncer schedules tasks to be executed on a separate thread, but only after a short delay.
 *
 * <p>The main use case is to schedule an analysis run (which can be expensive) that is triggered by
 * any change to a document. Document changes can happen rapidly (at the speed of keystrokes) but we
 * don't want to do a full analysis on every single keystroke.
 *
 * <p>This is heavily borrowed from the Kotlin language server.
 */
public class Debouncer {
  private Duration delay;

  private final ScheduledExecutorService workerThread = Executors.newScheduledThreadPool(1);

  private Future<?> pendingTask = null;

  public Debouncer(Duration delay) {
    this.delay = delay;
  }

  /**
   * Schedule a task to be run after a delay. If there is already a pending task, it will be
   * cancelled.
   */
  public void submit(Runnable task) {
    if (pendingTask != null) {
      pendingTask.cancel(false);
    }
    pendingTask = workerThread.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
  }

  public void setDelay(Duration delay) {
    this.delay = delay;
  }
}
