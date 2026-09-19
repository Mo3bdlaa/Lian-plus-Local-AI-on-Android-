package com.lian.plus.image;

// Progress and completion for one generation run. `oneway` so the worker
// process is never blocked by a slow client.
oneway interface IImageGenCallback {
    void onStep(int step, int totalSteps, float secondsForStep);
    void onComplete(String filePath, int width, int height, long elapsedMillis);
    void onError(String message);
}
