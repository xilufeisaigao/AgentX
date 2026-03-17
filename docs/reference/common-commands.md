# AgentX 常用命令

所有命令都按当前仓库的 Docker-first 运行方式整理。

## Docker 栈

查看状态：

```powershell
docker compose --env-file .env.docker ps
```

查看 backend 日志：

```powershell
docker compose --env-file .env.docker logs -f backend
```

停止：

```powershell
docker compose --env-file .env.docker down
```

连同卷一起清：

```powershell
docker compose --env-file .env.docker down -v
```

## 当前运行时 LLM 配置

```powershell
Invoke-RestMethod -Method Get -Uri 'http://127.0.0.1:18082/api/v0/runtime/llm-config' | ConvertTo-Json -Depth 6
```

## 最常用查询接口

session progress：

```powershell
Invoke-RestMethod -Method Get -Uri 'http://127.0.0.1:18082/api/v0/sessions/<SESSION_ID>/progress' | ConvertTo-Json -Depth 8
```

task board：

```powershell
Invoke-RestMethod -Method Get -Uri 'http://127.0.0.1:18082/api/v0/sessions/<SESSION_ID>/task-board' | ConvertTo-Json -Depth 8
```

ticket inbox：

```powershell
Invoke-RestMethod -Method Get -Uri 'http://127.0.0.1:18082/api/v0/sessions/<SESSION_ID>/ticket-inbox' | ConvertTo-Json -Depth 8
```

run timeline：

```powershell
Invoke-RestMethod -Method Get -Uri 'http://127.0.0.1:18082/api/v0/sessions/<SESSION_ID>/run-timeline?limit=30' | ConvertTo-Json -Depth 8
```

## 数据库查询

先取 DB 密码：

```powershell
$dbPass = (Get-Content .env.docker | Where-Object { $_ -match '^AGENTX_DB_PASSWORD=' } | Select-Object -First 1).Split('=')[1]
```

进入 MySQL：

```powershell
docker exec -it -e MYSQL_PWD=$dbPass agentx-mysql-1 mysql -u agentx -D agentx_backend
```

查某个 session：

```sql
select * from sessions where session_id = '<SESSION_ID>';
```

查某个 session 的任务总数：

```sql
select count(*) as task_count
from work_tasks t
join work_modules m on m.module_id = t.module_id
where m.session_id = '<SESSION_ID>';
```

查某个 session 的 run：

```sql
select tr.run_id, tr.run_kind, tr.status, tr.started_at, tr.finished_at
from task_runs tr
join work_tasks wt on wt.task_id = tr.task_id
join work_modules wm on wm.module_id = wt.module_id
where wm.session_id = '<SESSION_ID>'
order by tr.started_at desc;
```

查失败事件：

```sql
select tr.run_id, tr.run_kind, tr.status, tre.event_type, tre.body, tre.data_json
from task_run_events tre
join task_runs tr on tr.run_id = tre.run_id
join work_tasks wt on wt.task_id = tr.task_id
join work_modules wm on wm.module_id = wt.module_id
where wm.session_id = '<SESSION_ID>'
  and tr.status = 'FAILED'
order by tre.created_at desc;
```

## session repo 和 git 证据

查看某个 session repo：

```powershell
Get-ChildItem 'runtime-projects/default-repo/sessions/<session-id-lowercase>/repo' -Force
```

看 refs 历史：

```powershell
git -C runtime-projects/default-repo/sessions/<session-id-lowercase>/repo log --graph --decorate --oneline --all -n 50
```

## 容器内运行时目录

列出 context pack：

```powershell
docker exec agentx-backend-1 sh -lc "ls -R /agentx/runtime-data/context/context/task-context-packs | sed -n '1,80p'"
```

列出 task skill：

```powershell
docker exec agentx-backend-1 sh -lc "ls -R /agentx/runtime-data/context/context/task-skills | sed -n '1,80p'"
```

查看 backend 挂载：

```powershell
docker inspect agentx-backend-1 --format '{{json .Mounts}}'
```

## 生成项目二次验证

运行测试：

```powershell
docker run --rm -v "<HOST_PROJECT_PATH>:/workspace" -w /workspace maven:3.9.11-eclipse-temurin-21 mvn -q test
```

临时启动 Spring Boot：

```powershell
docker run -d --name codex-minimal-healthz-check -v "<HOST_PROJECT_PATH>:/workspace" -w /workspace -p 19095:8080 maven:3.9.11-eclipse-temurin-21 sh -lc "mvn -q spring-boot:run '-Dspring-boot.run.arguments=--server.port=8080'"
```

验证健康接口：

```powershell
Invoke-RestMethod -Method Get -Uri 'http://127.0.0.1:19095/api/healthz' | ConvertTo-Json
```

停止临时容器：

```powershell
docker rm -f codex-minimal-healthz-check
```
