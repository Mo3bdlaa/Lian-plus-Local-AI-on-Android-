package com.lian.plus.image;

import com.lian.plus.image.IImageGenCallback;

interface IImageGenService {
    boolean isEngineAvailable();
    String engineInfo();

    // Companion paths may be empty strings when the checkpoint is self-contained.
    boolean loadModel(String modelPath, String vaePath, String taesdPath,
                      int threads, boolean flashAttn, boolean convDirect);
    // oneway: unload waits on the worker thread, which may be minutes into a
    // generation. The caller must never block on that.
    oneway void unload();
    String modelVersion();

    // The result is written to outputPath rather than returned: a 1024x1024 PNG
    // is far past Binder's transaction limit, and both processes share the same
    // app sandbox anyway.
    void generate(String prompt, String negativePrompt,
                  int width, int height, int steps, float cfgScale,
                  long seed, int sampler, int scheduler,
                  String initImagePath, float strength,
                  String outputPath, IImageGenCallback callback);

    oneway void cancel();
}
