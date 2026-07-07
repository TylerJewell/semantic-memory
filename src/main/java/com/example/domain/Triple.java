package com.example.domain;

public record Triple(
    String subject, String predicate, String object, ProvenanceEnvelope envelope, boolean active) {}
