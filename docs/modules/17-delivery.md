# delivery 模块

## 职责

`delivery` 负责把 session 当前可交付结果发布成用户可 clone 的仓库：

- 发布 clone repo
- 查询当前有效发布
- 清理过期发布

它不负责决定“能不能交付”，只负责“怎么把交付物暴露出来”。

## 入站入口

- API:
  [DeliveryCloneController](../../src/main/java/com/agentx/agentxbackend/delivery/api/DeliveryCloneController.java)
  - `publishCloneRepo`
  - `getActiveCloneRepo`

## 主要表

当前 schema 没有 delivery 专属表。
发布状态主要由外部仓库和元数据文件维护。

## 关键代码入口

- 应用服务:
  [DeliveryClonePublishService](../../src/main/java/com/agentx/agentxbackend/delivery/application/DeliveryClonePublishService.java)
  - `publish`
  - `findActive`
  - `cleanupExpiredPublications`
- 外部适配器:
  [GitBareCloneRepositoryAdapter](../../src/main/java/com/agentx/agentxbackend/delivery/infrastructure/external/GitBareCloneRepositoryAdapter.java)
  - `publish`
  - `findActive`
  - `cleanupExpired`

## 在全链路里的位置

delivery 是 session 完成后的最后一步可见动作：

1. `session` 已完成
2. session repo 上已有 delivery tag 和最终代码
3. `delivery` 把它发布为 bare clone repo
4. 用户通过 `git://...` 拿走成果

## 想查什么就看哪里

- clone URL 是怎么生成的
  - 看 [GitBareCloneRepositoryAdapter](../../src/main/java/com/agentx/agentxbackend/delivery/infrastructure/external/GitBareCloneRepositoryAdapter.java)
- 为什么某个 session 现在还能拿到 clone repo
  - 看 `findActive`
- 为什么旧 clone repo 消失了
  - 看 `cleanupExpiredPublications`

## 调试入口

- API: `POST /api/v0/sessions/{sessionId}/delivery/clone-repo`
- API: `GET /api/v0/sessions/{sessionId}/delivery/clone-repo`
- 文件系统: `runtime-projects/default-repo/remotes`
- 网络入口: `git://127.0.0.1:19418`

## 工程优化思路

### 近期整理

- 补发布元数据说明，明确 repo 名、session id、过期时间的映射关系。
- 给发布失败补更清晰的错误信息和重试建议。

### 可维护性与可观测性

- 把 clone publish 的生命周期纳入 query/read model，减少只能靠文件系统判断。
- 为发布和清理补结构化日志。

### 中长期演进

- 把 delivery 从“临时 bare repo”扩展到多种交付形态，例如压缩包、镜像、制品仓。
- 支持更明确的 retention policy 和审计记录。
