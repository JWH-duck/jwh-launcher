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

## 3. 版本号维护（单一来源）

**发版时只需改工程根 `pom.xml` 的 `<version>` 一处**，其余位置全部自动派生，不要在代码、脚本、产物名里手填版本号：

- 运行时界面/更新比对：`Versions.current()` 依次读 `jpackage.app-version`（jpackage 打包注入）→ classpath `version.properties`（Maven filtering 从 `${project.version}` 注入）→ 兜底常量。
- 打包脚本（`tools/pack-deliver.ps1`、`tools/pack-update.ps1`）与发布脚本（`tools/release-upload.ps1`）均自动解析 pom 版本；发布脚本若手动传入 `-Version`，会与 pom 比对，不一致即中止。
- 产物文件名、manifest 的 `version`、Release 名称均取该版本号。

> 说明：`ModpackExporter` / `SkinServer` 里的 `1.0.0` 是导出整合包的格式版本号，与启动器版本无关，不要跟随改动。

## 4. 发布步骤

### 4.1 打包交付双包（应用 zip + 源码 zip）
```powershell
.\tools\pack-deliver.ps1
```
脚本先离线构建 `target/JWH-Launcher.jar`，再用 jpackage 生成 app-image、外置资源，产出：

| 产物 | 命名 |
|---|---|
| 应用全量包 | `JWH-Launcher-v<版本>.zip`（exe + app jar + 外置资源 + licenses） |
| 源码包 | `JWH-Launcher-src-v<版本>.zip` |

脚本内置包内校验：大资源不在 fat jar、无 jfxwebkit、license/媒体类/版本清单齐全、源码包关键文件在位。

### 4.2 打包更新件
```powershell
.\tools\pack-update.ps1
```
在应用全量包基础上生成更新件：

| 产物 | 命名 |
|---|---|
| 增量程序 jar | `JWH-Launcher-<版本>.jar` |
| 资源层 zip | `JWH-Launcher-<版本>-<层名>.zip`（relay / terracotta / music / neoform / neoforge / agent） |
| 全量分卷 | `JWH-Launcher-v<版本>.zip.part1` `.part2` ...（每卷 <80MiB，进 Gitee Release） |

### 4.3 生成 manifest 并上传 Gitee
```powershell
# 仅本地生成 manifest.json 并校验产物（不上传）
.\tools\release-upload.ps1 -SkipUpload

# 生成 manifest 并上传到 Gitee Release latest
$env:GITEE_TOKEN='<你的 Gitee 私人令牌>'
.\tools\release-upload.ps1 -Notes "本次更新说明"
```
版本号自动取 pom；`-Notes` 可选，写入 manifest 的更新说明，不传则用版本模板文案。
脚本会逐项校验产物大小与 SHA-256，生成双源 `urls` 清单（`urls[0]` 主源占位、`urls[1]` Gitee 兜底），把 manifest 和全部更新包上传到标签 `latest`，并打印下载直链。

### 4.4 双推源码
```powershell
git add -A
git commit -m "release: v<版本>"
git push gitee main
git push github main
```

## 5. 发布后校验

- manifest 在线拉取 200，版本号正确；在线 manifest 与本地 `manifest.json` SHA-256 一致。
- 抽查 jar、分卷直链匿名下载 200，字节数与清单一致。
- 确认 Release 附件数量：1 个 manifest + 1 个增量 jar + 6 个资源层 + N 个分卷。
- 两仓 `main` 远端 SHA 与本地一致：`git ls-remote gitee main` / `git ls-remote github main`。

## 6. manifest 字段契约（与 `Updater.java` 解析对齐）

```json
{
  "version": "1.0.0",
  "notes": "更新说明",
  "jar": { "urls": ["<主源>", "<Gitee 兜底>"], "size": 0, "sha256": "..." },
  "layers": [
    { "name": "relay", "version": "1.0.0", "urls": ["<主源>", "<Gitee 兜底>"], "size": 0, "sha256": "..." }
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

## 7. 容量与限制（Gitee 免费版）

- Release 单附件 ≤100MB；单仓库附件总量 ≤1GB。
- 仓库内单文件 ≤50MB。全量包因此切 80MiB 分卷上传。

## 8. 常见问题

- **GitHub 22 端口连不上**：改用 443（见第 2 节 SSH 配置）。
- **Gitee 推送卡在登录弹窗**：后台会话里凭据管理器 GUI 无法交互会挂死；改用私人令牌直连，不要依赖弹窗。
- **附件上传失败 / 超时**：单文件不超过 100MB；大文件用 `--max-time 600`；失败重传同名附件即可覆盖。
- **主源 `urls[0]` 仍是占位**：主静态源接入后，在 `release-upload.ps1` 的 `-PrimaryBase` 传入真实前缀；未接入前 Gitee 兜底单独生效。
