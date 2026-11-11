package com.promax.mode.shizuku;

interface IShizukuBridge {
    void destroy();
    void exit();
    String getIdentity();
    String startFrameStats(String packageName);
    String dumpFrameStats(String packageName);
    String getGpuUsage();
    String takeScreenshot();
    String startScreenRecording();
    String stopScreenRecording();
    String[] listSharedDirectory(String relativePath);
    boolean createDirectory(String relativePath);
    boolean renameEntry(String relativePath, String newName);
    boolean deleteEntry(String relativePath);
    String readTextPreview(String relativePath, int maxBytes);
}
