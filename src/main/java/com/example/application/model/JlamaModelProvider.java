package com.example.application.model;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;

import com.github.tjake.jlama.model.AbstractModel;
import com.github.tjake.jlama.model.ModelSupport;
import com.github.tjake.jlama.safetensors.DType;
import com.github.tjake.jlama.safetensors.SafeTensorSupport;

import akka.javasdk.agent.ModelProvider;

/**
 * Akka {@link ModelProvider.Custom} that serves an in-JVM Jlama model as the local
 * chat LLM. The model is downloaded and loaded lazily on first {@link #createChatModel()}
 * call — constructing this provider does NOT touch the filesystem or network.
 */
public final class JlamaModelProvider implements ModelProvider.Custom {

  private static final String MODEL_NAME = "tjake/TinyLlama-1.1B-Chat-v1.0-Jlama-Q4";
  private static final String MODEL_DIR = "./models";

  // A loaded Jlama model is a large (~1GB) heap structure; cache it per model name so repeated
  // requests (each of which constructs a fresh provider) reuse the same instance instead of
  // re-loading from disk every time.
  private static final ConcurrentHashMap<String, AbstractModel> CACHE = new ConcurrentHashMap<>();

  @Override
  public Object createChatModel() {
    return new JlamaChatModel(model());
  }

  @Override
  public Object createStreamingChatModel() {
    // Agents use non-streaming invoke(); no streaming model is provided.
    return null;
  }

  @Override
  public String modelName() {
    return MODEL_NAME;
  }

  private AbstractModel model() {
    return CACHE.computeIfAbsent(
        MODEL_NAME,
        name -> {
          try {
            File localModel = SafeTensorSupport.maybeDownloadModel(MODEL_DIR, name);
            return ModelSupport.loadModel(localModel, DType.F32, DType.I8);
          } catch (Exception e) {
            throw new RuntimeException("Failed to load Jlama model " + name, e);
          }
        });
  }
}
