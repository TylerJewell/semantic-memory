package com.example.application.embeddings;

import java.util.Random;

/**
 * Placeholder local embedding implementation with no external dependency.
 * Produces a deterministic, L2-normalized 768-length vector derived from the
 * input text so downstream code can be wired and tested before the real model
 * lands.
 *
 * <p>TODO(I2): replace with real in-JVM ONNX (all-mpnet-base-v2)
 */
public final class LocalOnnxEmbeddings implements Embeddings {

  private static final int DIMENSIONS = 768;

  public LocalOnnxEmbeddings() {}

  @Override
  public double[] embed(String text) {
    Random rnd = new Random(text.hashCode());
    double[] vec = new double[DIMENSIONS];
    double sumSq = 0.0;
    for (int i = 0; i < DIMENSIONS; i++) {
      vec[i] = rnd.nextGaussian();
      sumSq += vec[i] * vec[i];
    }
    double norm = Math.sqrt(sumSq);
    if (norm > 0) {
      for (int i = 0; i < DIMENSIONS; i++) {
        vec[i] /= norm;
      }
    }
    return vec;
  }

  @Override
  public String modelId() {
    return "all-mpnet-base-v2-PLACEHOLDER";
  }

  @Override
  public int dimensions() {
    return DIMENSIONS;
  }
}
