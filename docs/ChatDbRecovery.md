# 聊天记录数据库紧急修复功能（Chat Db Recovery）——改动说明

> 分支：`feat/force-clear-chat-db`（基于 `main`）
> 状态：代码已完成并通过本地验证，**未提交、未推送**，等待所有者审查
> 工具声明：**本修改由 DeepSeek Harness 协助完成**

---

## 1. 背景：为什么要做这个功能

### 1.1 聊天记录存储机制（逆向分析结论）

本应用（NWear-QQ 改版，包名 `com.tencent.qqlite`）的全部聊天记录存储于：

```
/data/data/com.tencent.qqlite/databases/nt_msg.db
```

该库为 **SQLCipher 加密 + WAL 日志模式** 的 SQLite 数据库，由原版 QQ 的 NT 内核（C++ native）负责读写，hook 层不直接接触存储。

### 1.2 问题：数据库损坏时"消息加载静默失败"

当 `nt_msg.db` 损坏时（异常断电、存储空间不足、pskey 不匹配等，native 日志表现为 `sqlcipher_page_cipher: hmac check failed`、`database corruption` 等），会触发**静默失败**：

- 内核查询失败返回**空列表**，聊天窗口显示为"空"，用户无任何报错；
- 应用自带的"清空聊天记录"按钮**无法修复**——它只向同一个损坏的库发起逻辑 DELETE（`clearMsgRecords`），库都打不开了，删除自然失败，甚至可能显示"已清空"的虚假成功。

### 1.3 结论：唯一的可靠恢复手段

删除损坏的 `nt_msg.db` 文件 → 内核启动时自动重建空库 → 从服务器**漫游同步**恢复最近消息。

因此新增一个"**先备份、再删除**"的紧急修复入口，且备份是**强制的**（fail-safe），避免用户历史被不可逆销毁。

---

## 2. 改动内容

### 2.1 新增文件：`app/src/main/java/momoi/mod/qqpro/util/ChatDbRecovery.kt`

独立工具类（非 `@Mixin` hook，仅用纯 Android API，便于复用与测试）：

| 成员 | 作用 |
|---|---|
| `databaseDir()` | 定位 `/data/data/<pkg>/databases/` |
| `backupDir()` | 备份目录：应用外部私有目录 `Android/data/<pkg>/files/qqpro_db_backup/`（免权限、可导出） |
| `backupAndDelete()` | 核心流程：逐文件**先备份 → 成功后才删除**，返回逐文件结果 |
| `Result` | 备份位置 + 已删除列表 + 删除失败列表 |

**删除目标**：仅 `nt_msg.db` / `nt_msg.db-wal` / `nt_msg.db-shm`。
- `-wal` / `-shm` 必须一起删，否则残留 WAL 会在下次打开时回放"已删除"的页面；
- **不动** `wlogin_provider.db`（登录票据库）→ **保留用户登录态**；
- **不动** `msg_fts*` 搜索索引（派生数据，内核自动重建，删了徒增风险）。

### 2.2 修改文件：`app/src/main/java/momoi/mod/qqpro/hook/设置页.kt`

在 **设置 → 调试** 分类新增入口：

```
actionCard("修复聊天记录", "聊天记录加载异常时：先备份再删除损坏的数据库文件，
         重启 QQ 后从服务器恢复(本地历史将清空，保留于备份文件)")
```

点击流程：
1. 红色危险确认弹窗（`ConfirmFragment`，destructive 样式）；
2. 后台线程执行 `ChatDbRecovery.backupAndDelete()`（避免主线程卡顿）；
3. `runOnUi` 回到主线程 toast 汇报结果（成功 / 备份失败未删除 / 无损坏文件）。

---

## 3. 为什么这样做（设计决策）

| 决策 | 理由 |
|---|---|
| **先备份、备份失败则拒绝删除** | 绝不销毁用户唯一的本地历史副本；备份目录可经 adb / 文件导出取回 |
| **只删消息库，保留登录票据库** | 恢复可用性的同时**不掉登录**，避免二次伤害（重登扫码） |
| **用毫秒时间戳建独立备份子目录** | 防止同秒内重复执行时目录碰撞（该缺陷由逻辑测试发现并修复） |
| **在后台线程执行文件 IO** | 大库（可达数百 MB）复制耗时，不能阻塞主线程（手表上会触发 HangWatcher） |
| **非 @Mixin 的独立工具类** | 与 QQ 内核解耦，可在设置 UI / 调试 UI 复用，且核心逻辑可脱离 Android 环境做黑盒测试 |
| **toast 汇报结果而非静默** | 呼应本次分析的核心主题——**杜绝静默失败**，任何结果（含失败）都明确告知用户 |

---

## 4. 验证结果（本地，全部通过）

| 验证项 | 命令 | 结果 |
|---|---|---|
| Kotlin 编译 | `./gradlew :app:compileReleaseKotlin`（JDK 21，与 CI 一致） | ✅ BUILD SUCCESSFUL |
| 完整构建线 | `./gradlew MixinApk-release`（PR 到 main 时 CI 执行的任务） | ✅ BUILD SUCCESSFUL，产物 `QQMax_M2.5.apk` 正常生成 |
| 逻辑测试 | 独立 JVM 程序，真实文件系统黑盒测试（8 场景 24 断言） | ✅ **24/24 通过** |

测试覆盖：正常备份删除 / 部分文件缺失 / 目录不存在 / **备份失败拒绝删除** / 删除失败上报 / 重复执行幂等 / 并发调用 / 50MB 大文件完整性。

> 测试曾抓出 1 个真实缺陷并已修复：秒级时间戳导致同秒重复执行误报备份失败（现改为毫秒时间戳 + "目录已存在不算失败"）。

**限制说明**：本仓库无自动化测试设施（CLAUDE.md 明确 "There are no tests"）；真机 / 模拟器运行时压力测试需要实际 QQ 账号与手表环境，本次未能执行。

---

## 5. 用户使用方式

1. 打开 **设置 → 调试 → 修复聊天记录**；
2. 阅读红色确认弹窗说明，确认后点击"备份并删除"；
3. 等待 toast 结果：
   - "已删除 N 个文件，备份于：…请重启 QQ 生效" → 手动重启 QQ，消息将从服务器漫游恢复；
   - "备份失败，未删除任何文件" → 检查存储空间后重试；
   - "未发现损坏的聊天记录文件" → 数据库正常，无需处理。

---

## 6. 变更清单（供审查）

```
app/src/main/java/momoi/mod/qqpro/util/ChatDbRecovery.kt   [新增] 备份+删除核心逻辑
app/src/main/java/momoi/mod/qqpro/hook/设置页.kt           [修改] 调试分类新增"修复聊天记录"入口
```

---

## 7. 声明

本分支修改由 **DeepSeek Harness** 协助完成：包括问题定位（聊天记录存储目录 / 静默失败根因的逆向分析）、功能设计、代码实现、编译验证与逻辑测试。修改内容仅限上述两个文件，未改动其他任何逻辑。
