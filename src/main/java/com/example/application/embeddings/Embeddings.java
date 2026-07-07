package com.example.application.embeddings;

public interface Embeddings {
  double[] embed(String text);

  String modelId();

  int dimensions();
}
