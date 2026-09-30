package com.bd2toolsbox;

interface IFileService {
    boolean copyFile(String sourcePath, String destPath);
    boolean copyDirectory(String sourceDirPath, String destDirPath);

    /**
     * 分页列举游戏资源目录。offset/limit 与返回的格式约定（T19 升级为 envelope，见 .version(7)）：
     *  - 返回 `{"ok":true,"entries":[...]}` 或 `{"ok":false,"error":"..."}`，不再用裸数组
     *    把「目录不存在」「读取异常」「确实为空」三种情况都编码成同一个 `[]`
     *  - 调用方循环拉取（如 limit=500），拼到 `ok:true` 且 entries 长度 < limit 即最后一页；
     *    任一页 `ok:false` 即整次列举失败，不把它当成最后一页
     *  - 单页 JSON 远小于 binder 1MB 事务上限 —— 全量拉取在 bundle 数
     *    1800+ 时会撑爆事务被截断（真机：JSONException Unterminated array）
     */
    String listBundleDirectoryPaged(String sharedDirPath, int offset, int limit);

    void destroy();
}
