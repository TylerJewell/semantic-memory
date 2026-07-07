package com.example.domain;

public record ProvenanceEnvelope(Layer layer, String source, java.time.Instant asserted, double conf) {}
