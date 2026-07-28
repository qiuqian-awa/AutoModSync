AutoModSync 3.2 使用说明

推荐流程：管理员先用正式客户端整合包进入服务器，处理仅服务端但错误声明双端的模组（在
automodsync_server_storage/forced_sync/custom/rules.json 中添加 IGNORE），完成一次严格同步并成功进服，
再分发已经生成 client_snapshot.json 的整合包。之后服务端每次清单变化都会产生新版本。

官方下载源：
1. 服务端默认按 SHA-1 自动查询 Modrinth 官方 API。
2. 如需 CurseForge 自动识别，在 automodsync-common.toml 中填写 curseForgeApiKey。
3. 无法自动识别的文件可写入 automodsync_server_storage/official_sources.json；键可使用文件名、modId
   或 SHA-1，值为官方 CDN 直链数组。仅接受 Modrinth CDN 和 Forge CDN 的 HTTPS 地址。
4. 客户端先尝试清单中的官方源；网络、状态码、大小或哈希任一校验失败后，自动改用服务器 /download，
   服务器再从当前 mods 或内容寻址仓库提供完全相同哈希的文件。

同步安全模型：下载先写入 staging，SHA-1、SHA-256 和大小全部通过后才应用；原文件进入事务备份。
应用或最终环境校验失败时会自动回滚。只有同步成功并在下一次连接同一服务器时再次通过完整校验，
客户端才会提交新版本快照。
