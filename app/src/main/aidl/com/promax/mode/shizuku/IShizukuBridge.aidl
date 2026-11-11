package com.promax.mode.shizuku;

interface IShizukuBridge {
    void destroy() = 16777114;
    void exit() = 1;
    String getIdentity();
    String startFrameStats(String packageName);
    String dumpFrameStats(String packageName);
    String getGpuUsage();
    String[] listSharedDirectory(String relativePath);
    boolean createDirectory(String relativePath);
    boolean renameEntry(String relativePath, String newName);
    boolean deleteEntry(String relativePath);
    String readTextPreview(String relativePath, int maxBytes);
}
