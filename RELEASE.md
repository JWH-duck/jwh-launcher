# JWH-Launcher 发布与更新分发流程

## 1. 仓库拓扑

| 远端 | 地址 | 作用 |
|---|---|---|
| Gitee（主） | https://gitee.com/JWH-Minecraft/jwh-launcher | 源码 + Release 更新分发兜底（manifest / 增量 jar / 资源层 / 全量分卷） |
| GitHub（镜像） | https://github.com/JWH-duck/jwh-launcher | 仅源码镜像，**不传 Release、不挂更新链接** |

更新清单默认地址（内置在 `Updater.java` 的 `DEFAULT_MANIFEST_URL`）：
`https://gitee.com/JWH-Minecraft/jwh-launcher/releases/download/latest/manifest.json`

## 2. 环境与凭据

- JDK 21（`javac/java/jpackage`）；Maven 离线构建（`mvnw.cmd -o package -DskipTests`）。
- Git 已安装；SSH 到 GitHub 走 443 端口（22 端口被阻），配置在 `~/.ssh/config`：
  ```
  Host github.com
    HostName ssh.github.com
    Port 443
    User git
    IdentityFile ~/.ssh/id_ed25519
  ```
- Gitee 上传使用私人令牌（勾选 `projects` 权限）。令牌不要写进 Git 配置或任何源码文件；用完可在 Gitee 吊销重建。

## 3. 发布步骤

### 3.1 构建程序 jar
在工程根目录执行离线构建，产出 `target/JWH-Launcher.jar`：
```
mvnw.cmd -o -q package -DskipTests
```

### 3.2 打包更新产物
打包脚本负责生成：增量 jar、六层资源 zip、全量 zip、全量 80MiB 分卷。
产物输出到交付目录（工程根的上级），命名约定：

| 产物 | 命名 |
|---|---|
| 增量程序 jar | `JWH-Launcher-<版本>.jar` |
| 资源层 zip | `JWH-Launcher-<版本>-<层名>.zip`（relay / terracotta / music / neoform / neoforge / agent） |
| 全量包 | `JWH-Launcher-v<版本>.zip`（超 Gitee 100MB，仅手动分发） |
| 全量分卷 | `JWH-Launcher-v<版本>.zip.part1` `.part2` ...（每卷 <80MiB，进 Gitee Release） |

### 3.3 生成 manifest 并上传 Gitee
```powershell
# 仅本地生成 manifest.json 并校验产物（不上传）
.\tools\release-upload.ps1 -Version 1.6.264 -SkipUpload

# 生成 manifest 并上传到 Gitee Release latest
$env:GITEE_TOKEN='<你的 Gitee 私人令牌>'
.\tools\release-upload.ps1 -Version 1.6.264 -Notes "本次更新说明"
```
`-Notes` 可选，写入 manifest 的更新说明；不传则用版本模板文案。
脚本会逐项校验产物大小与 SHA-256，生成双源 `urls` 清单（`urls[0]` 主源占位、`urls[1]` Gitee 兜底），把 manifest 和全部更新包上传到标签 `latest`，并打印下载直链。

### 3.4 双推源码
```powershell
git add -A
git commit -m "release: v<版本>"
git push gitee main
git push github main
```

## 4. 发布后校验

- manifest 在线拉取 200，版本号正确；在线 manifest 与本地 `manifest.json` SHA-256 一致。
- 抽查 jar、分卷直链匿名下载 200，字节数与清单一致。
- 确认 Release 附件数量：1 个 manifest + 1 个增量 jar + 6 个资源层 + N 个分卷。
- 两仓 `main` 远端 SHA 与本地一致：`git ls-remote gitee main` / `git ls-remote github main`。

## 5. manifest 字段契约（与 `Updater.java` 解析对齐）

```json
{
  "version": "1.6.264",
  "notes": "更新说明",
  "jar": { "urls": ["<主源>", "<Gitee 兜底>"], "size": 0, "sha256": "..." },
  "layers": [
    { "name": "relay", "version": "1.6.264", "urls": ["<主源>", "<Gitee 兜底>"], "size": 0, "sha256": "..." }
  ],
  "size": 0,
  "sha256": "...",
  "parts": [
    { "index": 1, "name": "<分卷名>", "urls": ["<主源>", "<Gitee 兜底>"], "size": 0, "sha256": "..." }
  ]
}
```
- `urls` 数组按顺序候选，坏包自动换源；兼容旧的单 `url` 字段。
- 分卷由客户端按序字节合并，再校验全量包整体 `size` 与 `sha256`。

## 6. 容量与限制（Gitee 免费版）

- Release 单附件 ≤100MB；单仓库附件总量 ≤1GB。
- 仓库内单文件 ≤50MB。全量包因此切 80MiB 分卷上传。

## 7. 常见问题

- **GitHub 22 端口连不上**：改用 443（见第 2 节 SSH 配置）。
- **Gitee 推送卡在登录弹窗**：后台会话里凭据管理器 GUI 无法交互会挂死；改用私人令牌直连，不要依赖弹窗。
- **附件上传失败 / 超时**：单文件不超过 100MB；大文件用 `--max-time 600`；失败重传同名附件即可覆盖。
- **主源 `urls[0]` 仍是占位**：主静态源接入后，在 `release-upload.ps1` 的 `-PrimaryBase` 传入真实前缀；未接入前 Gitee 兜底单独生效。
