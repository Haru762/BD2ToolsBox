package com.bd2toolsbox;

interface IFileService {
    boolean copyFile(String sourcePath, String destPath);
    boolean copyDirectory(String sourceDirPath, String destDirPath);

    /**
     * 分页列举游戏资源目录。offset/limit 与返回的格式约定：
     *  - 调用方循环拉取（如 limit=500），拼到返回的数组长度 < limit 即最后一页
     *  - 单页 JSON 远小于 binder 1MB 事务上限 —— 全量拉取在 bundle 数
     *    1800+ 时会撑爆事务被截断（真机：JSONException Unterminated array）
     */
    String listBundleDirectoryPaged(String sharedDirPath, int offset, int limit);

    void destroy();
}
