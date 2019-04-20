package com.soartech.soarls;

/**
 * This structure describes the configuration that clients send to the server via the
 * workspace/didChangeConfiguration notification.
 */
class Configuration {
  /** How long in milliseconds to wait for changes to stop before an analysis is begun. */
  public Integer debounceTime = 1000;

  /** Whether hover tooltips should show full comment text or just the first line. */
  public Boolean fullCommentHover = false;
}
