package com.example.application.ingest;

/** Classification of a derived triple against the store by the ingest gate. */
public enum Outcome {
  NEW,
  CORROBORATING,
  CONFLICTING,
  SUPPRESSED,
  REJECTED
}
