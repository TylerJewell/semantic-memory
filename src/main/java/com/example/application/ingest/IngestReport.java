package com.example.application.ingest;

/** Mutable counters accumulated across a batch of ingest classifications. */
public final class IngestReport {
  private int newCount;
  private int corroborating;
  private int conflicting;
  private int suppressed;

  public void add(Outcome outcome) {
    switch (outcome) {
      case NEW -> newCount++;
      case CORROBORATING -> corroborating++;
      case CONFLICTING -> conflicting++;
      case SUPPRESSED -> suppressed++;
    }
  }

  public int newCount() {
    return newCount;
  }

  public int corroborating() {
    return corroborating;
  }

  public int conflicting() {
    return conflicting;
  }

  public int suppressed() {
    return suppressed;
  }

  public String summary() {
    return "new="
        + newCount
        + " corroborating="
        + corroborating
        + " conflicting="
        + conflicting
        + " suppressed="
        + suppressed;
  }
}
