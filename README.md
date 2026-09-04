# QQ NekoRewrite 🐾

一个 LSPosed 模块：把你在 QQ 里发出的消息交给 AI 改写成**猫娘口吻**后发送。

> 「喵~ 主人的消息已经被改写好啦 ♪」

基于 Hook QQ 9.1.35 的 `MsgService.sendMsg` 实现，在消息离开输入框、进入发送管线时静默拦截，AI 改写完成后自动发出——聊天界面内完全无感。

## ✨ 功能

- **猫娘改写** — 调用 OpenAI 兼容的 Chat API，将待发文本改写为猫娘语气；人设提示词可自定义
- **多 AI 提供方** — 内置 DeepSeek / OpenAI / 智谱 GLM / 通义千问 / Kimi / 硅基流动预设，也可自定义端点；切换提供方自动填充端点与默认模型
- **检查连接 / 获取可用模型** — 一键探测端点与 API Key 连通性，直接从 `/models` 拉取真实模型列表填充下拉框
- **异步改写** — 改写请求在独立队列执行，不阻塞发送线程；相同请求自动合并（并发去重），改写结果 LRU 缓存（上限 200 条）省 token
- **联系人过滤** — 白名单 / 黑名单 / 不限制三种模式，只改写想改写的会话
- **`//` 转义前缀** — 以 `//` 开头的消息跳过改写、去掉前缀原样发送，不消耗 token
- **通知栏快速开关** — 常驻低优先级通知，一键启停模块
- **改写 Toast** — 可选在发送时 Toast 显示实际发出的改写结果
- **Material You** — 全 Material 3 界面，Android 12+ 动态取色跟随壁纸，沉浸式系统栏

## 📋 要求

| 项目 | 要求 |
|---|---|
| QQ | 9.1.35（其他版本未测试，理论兼容） |
| 框架 | LSPosed（Zygisk） |
| 系统 | Android 8.0+（API 26；Android 12+ 可体验动态取色） |
| 其他 | 任一 OpenAI 兼容 Chat API 的 Key |

## 📲 安装

1. 从 [Releases](https://github.com/Makuro-Arisaka/QQNekoRewrite/releases) 下载 APK 并安装
2. 在 LSPosed 管理器中启用 **QQ NekoRewrite**，作用域勾选 **QQ**
3. 重启 QQ
4. 打开模块设置页，填入 API 端点与 API Key，点击「检查连接」验证，保存即可

## ⚙️ 工作原理

```
QQ 输入框发送
    │
    ▼
MsgService.sendMsg (全部 10 个重载)
    │  hookAllMethods 兜底，反射按字段识别参数
    ▼
MessageInterceptor ──► ContactFilter（白/黑名单）
    │                     │
    │  `//` 前缀？ ──► 去前缀原样放行
    ▼
AiRewriteEngine ──► RewriteCache（LRU 命中直接返回）
    │  并发合并：相同请求等待复用
    ▼
ChatApi（OpenAI 兼容 /chat/completions）
    │
    ▼
猫娘版消息 ──► invokeOriginalMethod 重放
```

### 配置跨进程同步

模块运行在 QQ 进程，设置页在模块自身进程。配置通过**三通道冗余**（QQ 侧 SharedPreferences / `XSharedPreferences` / JSON 快照）+ `lastUpdated` 时间戳仲裁同步，QQ 重启后即使没有广播也能读到最新配置；运行中修改则通过广播即时生效。

## 🔨 自行构建

```bash
# 需要 JDK 17+，依赖已配置代理缓存时可直接离线构建
./gradlew assembleDebug          # 调试包
./gradlew assembleRelease        # 未签名 release（传入 KEYSTORE_PASS 环境变量则自动签名）
```

> 本仓库不含签名密钥。`*.keystore` 已被 `.gitignore` 排除，请自建 keystore 或使用 debug 包。

## 📁 目录结构

```
app/src/main/java/com/neko/rewrite/
├── MainHook.kt            # Xposed 入口，hook 注册 / 配置加载 / 快速开关
├── MessageInterceptor.kt  # sendMsg 拦截与改写后重放
├── ContactFilter.kt       # 联系人白/黑名单过滤
├── AiRewriteEngine.kt     # 改写队列、并发合并、超时兜底
├── RewriteCache.kt        # 改写结果 LRU 缓存
├── ChatApi / ProviderPresets / PromptManager
├── ConfigManager.kt       # 三通道跨进程配置同步
├── QuickToggle.kt         # 通知栏快速开关
├── ApiProbe.kt            # 连接探测 / 模型列表拉取
└── MainActivity + ui/     # Material 3 设置界面
```

## ⚠️ 免责声明

本项目仅供学习交流，请勿用于骚扰、欺骗等场景。改写后的消息由 AI 生成，发送前请自行确认内容（建议开启「改写 Toast」）。使用本模块产生的一切后果由使用者自行承担。

## License

见 [LICENSE](LICENSE)
