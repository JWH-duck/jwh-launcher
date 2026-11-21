# JWH Launcher — 类似 PCL / HMCL 的 Minecraft 启动器

Java + JavaFX 编写的 Minecraft Java 版启动器，功能对齐主流第三方启动器：

- **正版登录**：Microsoft 官方 OAuth 流程（内嵌浏览器授权，自动刷新令牌）
- **离线登录**：任意用户名，官方一致的离线 UUID 生成规则
- **版本隔离**：开关式——开启后每个版本独立的 saves / mods / config（类似 PCL 的“版本隔离”）
- **游戏版本下载**：官方版本清单，正式版 / 快照一键安装（jar + 依赖库 + 资源文件）
- **加载器安装**：Fabric / Quilt / Forge / NeoForge
- **Mod 下载**：Modrinth 官方 API 搜索，按 MC 版本 + 加载器过滤，自动放入 mods
- **光影**：Iris 一键安装 + 光影包搜索下载（shaderpacks）+ OptiFine（镜像获取安装包）
- **材质包下载**：Modrinth 资源包分类，自动放入 resourcepacks
- **双数据源**：Mod / 材质包搜索可切换 Modrinth（免 Key）/ CurseForge（需免费申请 Key，未填 Key 自动网页搜索兜底）
- **界面美化**：专属 JWH 图标（生成 + 转 ICO），深色渐变主题、导航高亮、悬停动效
- **免 Java 分发**：jpackage 打包 `dist/JWH-Launcher/` 应用目录，内含 JWH-Launcher.exe + 自带 JRE，双击即用

## 目录结构

```
jwh-launcher/
├── pom.xml                     # Maven 配置（JavaFX 21 + JSON + shade 打包）
├── src/main/java/com/jwh/launcher/
│   ├── Main.java               # 入口
│   ├── LauncherApp.java        # 主窗口（导航 / 顶栏账号 / 状态栏）
│   ├── auth/                   # 微软 OAuth + 离线登录 + 账号管理
│   ├── config/                 # 配置持久化（config.json）
│   ├── game/                   # 版本清单 / 安装器 / 启动器 / Java 检测
│   ├── loader/                 # Fabric / Quilt / Forge / NeoForge 安装
│   ├── download/               # 并发下载 / Modrinth API / CurseForge API / OptiFine 镜像
│   ├── resources/              # 图标（icon.png / icon.ico）+ 全局样式（style.css）
│   └── ui/                     # JavaFX 界面
└── README.md
```

## 环境要求

- **JDK 21+**（本机已装：Microsoft OpenJDK 21）
- **Maven 3.9+**（或直接用 IDEA，自带 Maven）
- Windows / macOS / Linux 均可

## 运行方式（三选一）

### 方式零：免 Java 的 exe（不需要装 Java）

```bat
cd dist\JWH-Launcher
JWH-Launcher.exe
```

`dist\JWH-Launcher` 是 jpackage 生成的应用目录：exe 启动器 + 自带 Java 运行时，双击即用。

### 方式一：IntelliJ IDEA（推荐，最简单）

1. `File → Open`，选择本项目目录 `jwh-launcher`，等待 Maven 导入完成
2. 打开 `src/main/java/com/jwh/launcher/Main.java`
3. 点击类名旁绿色三角 **Run 'Main.main()'**

### 方式二：命令行（Maven）

先安装 Maven（如已有可跳过）：
1. 下载 https://maven.apache.org/download.cgi 的 `apache-maven-3.9.x-bin.zip`，解压到任意目录
2. 把 `解压目录\bin` 加入系统 PATH（或用绝对路径调用 mvn.cmd）

然后：

```bat
cd jwh-launcher
mvn clean package
java -jar target\JWH-Launcher.jar
```

国内网络构建较慢时，可在 `~/.m2/settings.xml` 加阿里云镜像（可选）：

```xml
<settings>
  <mirrors>
    <mirror>
      <id>aliyun</id>
      <mirrorOf>central</mirrorOf>
      <url>https://maven.aliyun.com/repository/public</url>
    </mirror>
  </mirrors>
</settings>
```

## 使用流程

1. **登录**：点顶栏右侧「登录」→ 微软正版登录（弹窗内用 Microsoft 账号授权）或离线登录（填用户名）
2. **装版本**：下载中心 → 游戏版本 → 选版本 → 安装（官方源，首次较慢属正常）
3. **装加载器**：下载中心 → 加载器 → 选 MC 版本和平台 → 安装
4. **装 Mod / 光影 / 材质包**：对应 Tab 搜索 → 选择目标版本 → 下载
5. **启动**：主页选版本 → 选 Java（自动检测）→ 调内存 → 启动游戏

## 下载源说明（按你的选择：官方源优先）

| 内容 | 来源 |
|---|---|
| 游戏版本元数据 / 主程序 / 依赖库 / 资源文件 | 官方：Mojang（piston-meta / libraries.minecraft.net / resources.download.minecraft.net / piston-data）；国内可切 **BMCLAPI 镜像**（设置页下载源切换，PCL/HMCL 同款） |
| Fabric / Quilt | 官方 meta 接口（meta.fabricmc.net / meta.quiltmc.org） |
| Forge / NeoForge | 官方 promotions 清单 + 官方 installer jar |
| Mod / 光影包 / 材质包 | 默认 Modrinth 官方 API；可在 Tab 内切换到 CurseForge 官方 API |
| CurseForge | 需要免费开发者 Key：登录 https://console.curseforge.com → My API Key 生成，粘贴到设置页。未填 Key 时自动打开网页搜索兜底 |
| OptiFine | **官方下载页含浏览器校验、没有开放 API**，社区统一采用 BMCLAPI 镜像获取安装包（HMCL 等同样做法），下载后弹出官方安装器 GUI 完成安装 |

国内网络访问官方源较慢属正常现象。**下载慢 / 下载失败时，在设置页把「下载源」切到「BMCLAPI 国内镜像」，保存后再点主页的「修复版本」即可自动补全**；版本下载支持断点续传与失败重试。

## 常见问题

- **启动失败提示 Java 版本不符**：1.16.5 及更早需 Java 8；1.17–1.20.4 需 17+；1.20.5+ 需 21。主页会给出推荐提示，在主页“选择其他 Java…”指定正确的 java。
- **正版登录无响应**：确保网络能访问 login.live.com；公共 client_id（`00000000-402b-4cd3-a82b-c45ab2f1d3f7`）为社区通用，若被微软回收需在 `auth/MicrosoftAuthenticator.java` 替换为自有 Azure 应用 client_id。
- **Forge 安装失败**：官方安装器需联网下载大量依赖，耐心等待；失败时按提示手动运行 installer jar（GUI）。
- **Mod 下载后游戏里看不到**：确认版本与加载器匹配（例如 Fabric 模组不能用于 Forge 版本）。

## 配置说明

启动器的 `config.json` 保存在固定位置，**jar / exe / 任何运行方式共用一份配置，不会因为换启动方式而丢失**：

- Windows：`%APPDATA%\JWH-Launcher\config.json`
- macOS / Linux：`~/.jwh-launcher/config.json`

旧版（存放在启动器目录）的配置会自动迁移过来。可手改或界面设置：

| 字段 | 说明 |
|---|---|
| minecraftDir | .minecraft 目录（默认 %APPDATA%\\.minecraft） |
| versionIsolation | true = 开启版本隔离 |
| maxMemory / minMemory | 内存分配（MB） |
| javaPath | 手动指定 java 可执行文件 |
| downloadThreads | 下载并发线程数 |
| jvmArgs | 附加 JVM 参数 |
| curseForgeKey | CurseForge 开发者 API Key（可选，用于 CurseForge 数据源） |
| accounts | 账号列表（微软账号含 refresh token，用于自动续期） |

## 实现要点（供二次开发）

- 游戏安装布局与原版启动器完全一致（libraries / assets / versions），可互通
- Microsoft 登录：授权码 → 微软 token → Xbox Live → XSTS → Minecraft Services（api.minecraftservices.com），refresh_token 自动续期
- 版本 JSON 解析支持 1.13+ arguments 新格式与 1.12.2- minecraftArguments 旧格式，含 rules（os/arch）过滤
- 原生库（natives）下载后自动解压并按规则过滤，启动时注入 -Djava.library.path
