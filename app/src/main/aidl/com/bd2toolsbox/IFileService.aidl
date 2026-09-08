package com.bd2toolsbox;

interface IFileService {
    boolean copyFile(String sourcePath, String destPath);
    boolean copyDirectory(String sourceDirPath, String destDirPath);
    String listBundleDirectory(String sharedDirPath);
    void destroy();
}
