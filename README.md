# AICallAgent · 超级小爱 AI 通话接管

`io.github.cherry1jay.aicallagent`

把小米「AI 通话」的大脑换成你自己的大模型。

一个 **LSPosed 模块**：接管ai通话（6.1.6）的 AI 自动接听，用任意 OpenAI 兼容接口（DeepSeek / Kimi / Qwen / GLM / Ollama / one-api 中转…）生成对话，并按来电类型分流——想接的走你的模型，不想接的交回小米原生话术。

> **本项目是 [AICallAgent](https://github.com/cherry1jay/AICallAgent) v1.4 的适配分支。**
> 原版针对小爱 **8.2.70**（`com.aios.osbot.*` 引擎），在旧版上是静默失效的——两代架构完全不同。
> 本分支在 **Redmi K50 Ultra / HyperOS 2.0.215 / Android 15 / ai通话6.1.6** 上真机实测通过。

---

## 它解决什么问题

小米的 AI 通话本身能自动接、能说话，但说什么由小爱决定：话术固定、不会随机应变、不能接入你自己的模型，也没法按来电类型区分对待——推销电话和外卖小哥听到的是同一套词。

本模块把「生成回复」这一步换掉，接听、字幕、语音合成还是系统原生。

- 界面、录音、字幕、通话记录照常；
- 不碰音频流，全程在文本态工作，不需要任何音频权限；
- 换成任意 OpenAI 兼容模型，提示词随你写；
- 外卖、快递、推销、客服、联系人、陌生号分别配置，每类可单独关掉（关掉就是原生小米回复）；
- **语音、字幕、通话记录三者文本一致**（这是 7.13 适配的关键难点，见下文）。

---

## 为什么原版在旧版超级小爱7.13 上失效

原版 hook 的是一条叫 `osbot` 的通话引擎链，7.13 把它整个换掉了。

**HyperOS系统新的ai通话引擎在超级小爱（8.2.70），而旧版的不在超级小爱，在ai通话（com.xiaomi.aiasst.service 6.1.6）里面**

实测比对：

| 原版（8.2.70） | 旧版ai通话（6.1.6） |
|---|---|
| 包名 `com.miui.voiceassist` | AI 通话引擎迁到 **`com.xiaomi.aiasst.service`** |
| `com.aios.osbot.tools.phone.engine.CallConversationEngine` | ❌ 不存在（无 `osbot` 包） |
| `handleCallerSentence(String)` | ❌ 不存在 |
| `takeOverCallWithAi()` | ❌ 不存在 |
| 字段 `speakFn` / `onAgentTurnComplete` | ❌ 不存在 |
| Provider `ai-assist-call-config` | 改为 `com.xiaomi.aiasst.service.aicall.provider` |
| 配置键 `ai_custom_opening_*` / `ai_call_reply_log_content` | ❌ 不存在 |

7.13 是全新的**事件驱动 + 云端 NLP**架构，ASR / NLP / TTS 全部在 `com.xiaomi.aiasst.service`（`classes2.dex`）：

```
process/asr/AsrManager            语音识别
process/nlp/NlpManager            对话大脑（发云端请求）
process/tts/TtsManager            语音合成（AudioTrack 播报）
process/ProcessManage             流程调度
incall/InCallAiServiceImpl        InCallService 实现
```

所以原模块在小爱进程里 `findClass` 全部落空、被 catch 吞掉，表现就是「装了、激活了、但一点反应都没有」。

---

## 适配后的 hook 点

对方说话，ASR 出文本，模块在这条链路上拦三处：

```
对方说话
  └─ ASR
       └─ NlpManager.doNlp(query, type)                    ← hook ①  拿到对方说的话
            │   query = ASR 文本
            │   type  = auto_answer / user_custom_query / first_do / normal
            │
            ├─ [本模块] 查来电类型 → 挑提示词 → 调你的大模型
            │
小米云端 NLP 返回原生回复
  ├─ ProcessManage.notifyPresenterOnNlpBack(…, bean)       ← hook ②  字幕 + 通话记录
  │     └─ 把 bean.text 改成模型回复
  └─ TtsManager.buildTtsEvent(text)                        ← hook ③  语音播报
        └─ 把 text 改成模型回复
```

三个设计要点：

1. **只换文本，不换嘴**。播报仍走引擎自己的 `TtsManager` → `doTts` → `AudioTrack`，音频注入、回声消除、打断处理全部原生。不碰音频流，也就不会和系统抢麦克风。
2. **字幕必须单独改**。小爱把 NLP 结果同时喂给 TTS（语音）和 `CallScreenCommonBean.text`（字幕/记录）两条路。只改 `buildTtsEvent` 会出现「听到的是你的模型、看到的还是小爱的话」——这正是 7.13 适配最容易踩的坑。`notifyPresenterOnNlpBack` 是字幕的唯一源头，改这里才能三者一致。
3. **失败必须放行原生**。模型超时（3 秒）/ 返回空 / 没配 Key，一律不拦截，直接让小爱原生回复继续。通话绝不因为模块而中断。

---

## 功能

**按来电类型分流**（6 类，各自独立开关）

| 类型 | 识别依据 | 默认 |
|---|---|---|
| 外卖送餐 | 黄页标签命中 外卖、送餐、美团、饿了么、配送、跑腿… | 接入 |
| 快递物流 | 快递、物流、驿站、顺丰、圆通、中通、菜鸟、包裹… | 接入 |
| 广告推销 | 骚扰、推销、广告、中介、保险、贷款、理财、催收、诈骗… | **交回原生** |
| 客服官方 | 客服、官方、银行、航空、运营商、政务、医院、售后… | 接入 |
| 通讯录联系人 | 号码在通讯录里（模块自查 `PhoneLookup`） | 接入 |
| 陌生号码 | 以上都不命中 | 接入 |

黄页查不到标签时按号码特征兜底：5–6 位短号、`400`/`800` 开头判客服，`95` 开头短号判客服。

**每类可自定义提示词**

点类别名直接编辑，改过的标「· 已改」，弹窗里可「恢复默认」。内置 6 套按语音场景写的默认话术——纯口语、短句、不带列表符号，因为这句话是要被 TTS 念出来的。

**手动「小爱帮我回」**

人工接听的通话，点通话页右上角 AI 应答 → 选「小爱帮我回」。

7.13 里没有原版的 `takeOverCallWithAi` 可 hook，但手动触发后对话回合**仍然会走 `doNlp`**，只是 `type` 标记为 `user_custom_query`。模块据此自动识别为「手动接管」，并豁免来电分类开关——用户主动点了按钮就是要 AI 接，不该被「骚扰电话走原生」这条规则挡回去。

**模型接入**

- 任意 OpenAI 兼容 `/chat/completions` 端点，填 Base URL + 模型名 + API Key
- 默认 `https://api.deepseek.com/v1` + `deepseek-chat`
- **允许模型思考（默认关闭）**：关闭时请求里明确要求直接作答、token 预算压到 320，并且**绝不把 `reasoning_content` 当回复**——正文为空就判这轮失败、放行原生，宁可不说话也不念内心戏
- 输出统一净化：剥掉思考区块和 markdown，按句子边界截到 80 字以内
- 超时秒数、保留对话轮数、全局人设提示词均可配

**通话记录**

每次通话逐轮记录「对方说 / 我说 / 耗时 / 判定类型」，用于调提示词和排查。

---

## 安装

**前提**：已 root（KernelSU / Magisk / APatch）+ LSPosed；系统「AI 通话」本身可用（设置 → 电话 → AI 通话，开启自动接听）。

1. 安装 `AICallAgent-1.8-sub.apk`（应用列表里显示为「AI 通话接管」）
2. 打开 LSPosed → 模块 → 启用 **AICallAgent**
3. 作用域**只勾选 `com.xiaomi.aiasst.service`** —— 注意不是「小爱同学」（`com.miui.voiceassist`），两者是不同进程
4. 软重启引擎进程让 hook 生效（**不用重启手机**）：
   ```bash
   adb shell am force-stop com.xiaomi.aiasst.service
   adb shell am start -n com.xiaomi.aiasst.service/.aicall.settings.main.CallLogAndSettingsActivity
   ```
5. 打开 AICallAgent → 填 Base URL / 模型 / API Key → **保存设置**
6. 打一通电话，开 AI 接听，听它说的是不是你的模型的话

### 验证注入成功

```bash
adb logcat -d | findstr AICallAgent
```

正常应看到：

```
AICallAgent: 注入成功（完整版）pkg=com.xiaomi.aiasst.service
AICallAgent: hook doNlp 成功
AICallAgent: hook buildTtsEvent 成功
AICallAgent: hook notifyPresenterOnNlpBack 成功
AICallAgent: 来电号码：138xxxxxxxx /
AICallAgent: 归类：陌生号码（联系人=false，黄页=null）
AICallAgent: [陌生号码] 模型回复（897ms）：您好，请问哪位？
AICallAgent: 已替换字幕文本：xxx -> 您好，请问哪位？
AICallAgent: 已替换播报文本：xxx -> 您好，请问哪位？
```

> **两个排查提示**
> - LSPosed 把模块日志归在 `LSPosedFramework` 标签下，用 `findstr AICallAgent` 匹配的是日志正文。
> - logcat 环形缓冲区只有 64 KiB。通话时大量系统日志会把进程启动那几行注入日志冲掉，所以建议**先清日志 → 强停引擎 → 立即 `-d` dump**，而不是打完电话再抓。

### 配置示例

| 服务 | Base URL | 模型 |
|---|---|---|
| DeepSeek | `https://api.deepseek.com/v1` | `deepseek-chat` |
| 月之暗面 | `https://api.moonshot.cn/v1` | `moonshot-v1-8k` |
| 智谱 | `https://open.bigmodel.cn/api/paas/v4` | `glm-4-flash` |
| Ollama（局域网） | `http://192.168.1.x:11434/v1` | `qwen2.5:7b` |
| one-api 中转 | 你的中转地址 + `/v1` | 对应模型名 |

---

## 构建

不需要 Gradle / Android Studio，只要 **JDK 11+** 和 **Android SDK**（build-tools + 任一 platform）：

```bash
export JAVA_HOME=/path/to/jdk
export ANDROID_HOME=/path/to/Android/Sdk
bash build.sh
# 产物：AICallAgent-1.4.apk（版本号默认值，可用 VERSION_NAME 覆盖）

# 指定版本号（tag 与 APK 名都跟着变）
KS_PASS=aicallagent VERSION_NAME=1.9 VERSION_CODE=11 bash build.sh
```

脚本流程：`aapt2 compile` → `aapt2 link` → `javac` → `d8` → `jar` 塞 dex → `zipalign` → `apksigner`。

- 首次构建会自动生成 `keystore.jks`（密码 `aicallagent`），**请自行保管**，后续升级要用同一个签名
- 已有 keystore 时用 `KS_PASS=你的密码 bash build.sh`
- `libs/api82.jar` 是 Xposed API 82（Apache-2.0），已内置，clone 即可构建
- 换图标：改 `genicon.py` 后 `python genicon.py`
- 本分支已修复原版 `build.sh` 在非 C/D 盘符（如 E 盘）下 javac 找不到源文件的路径转换问题

---

## 更新日志

### v1.8 — 字幕与语音同步、手动接管适配（本分支）

针对 7.13 的两个体验缺陷：

**1. 听到的和看到的不是同一句话**

小爱把 NLP 回复同时送给 TTS（语音）和 `CallScreenCommonBean`（字幕/通话记录）。此前只 hook 了 `buildTtsEvent`，于是「语音是模型、字幕是原生」。

修法：在 `ProcessManage.notifyPresenterOnNlpBack` 里把第 4 个参数 bean 的 `text` 字段覆盖成模型回复。真机验证每轮字幕与语音一致。

**2. 手动「小爱帮我回」不走模型**

7.13 没有 `takeOverCallWithAi`。改为识别 `doNlp` 的 `type == user_custom_query`（手动触发路径），自动豁免分类开关。

### v1.4 — 7.13 基础适配（本分支）

- 作用域 `com.miui.voiceassist` → `com.xiaomi.aiasst.service`
- hook 点全部换成 7.13 的 `NlpManager.doNlp` / `TtsManager.buildTtsEvent`
- 来电号码改从 `InCallAiServiceImpl.h()`（public static）取当前 `android.telecom.Call`
- 配置读取通道白名单同步改为 `com.xiaomi.aiasst.service`
- 保留原版全部功能：分类分流、分类提示词、通话记录、开场白、对话历史

### 原版 v1.4（8.2.70）改动摘要

- 删除 `content = reasoning_content` 兜底，正文为空判失败，不再把思考当答案念出来
- 新增「允许模型思考」开关（默认关）
- 播报超时改为按字数动态计算 `6s + 字数 × 400ms`，上限 60s
- 播报文本硬性上限 80 字，按句子边界截断

---

## 已知限制

- **强依赖类名/方法名**。整套 hook 按名字反射定位，小爱升级后若改名、混淆或再次重构，模块会**静默失效**（日志里出现 `hook xxx 失败`）。目前没做方法签名兜底。
- **版本绑定**。已适配 7.13.35.0012。其它 7.x 版本可能可用也可能失效，请以日志为准。
- **手动接管靠类型推断**。7.13 没有显式的接管方法可 hook，靠 `user_custom_query` 识别；若某些手动路径用了别的 `type`，可能不会豁免分类开关。
- **黄页标签可能取不到**。日志里 `黄页=null` 时分类会退回通讯录 + 短号规则。外送/快递/推销识别不准时，请附上 `归类：` 日志反馈。
- **只在文本态工作**。语音识别错误、方言、噪音，模块无能为力——它拿到的就是 ASR 的输出。
- **强制思考的模型关不掉思考**。像 `deepseek-flash` 这类推理模型，即便开关关着、提示词要求直接作答，响应里仍会出现 `reasoning_content` 并占用 token 预算。正文一旦被挤空，这一轮会放行原生而不是念思考。要彻底避免请用 `deepseek-chat`、`qwen-plus` 等非推理模型。
- **不做身份伪装**。默认话术是「机主现在不方便接电话」，但模型仍可能被套话。涉及验证码、转账、密码的场景，建议直接关掉对应类型的接入。

---

## 目录结构

```
AICallAgent/
├── AICallAgent-1.8-sub.apk              # 预编译产物（可选，也可自行构建）
├── build.sh                             # 一键构建脚本
├── genicon.py                           # 图标生成
├── keystore.jks                         # 签名（密码 aicallagent）
├── libs/api82.jar                       # Xposed API 82
├── README.md
├── LICENSE
└── XiaomiAICallAgent/
    ├── AndroidManifest.xml              # 模块声明 + 作用域
    ├── assets/xposed_init               # 模块入口类
    ├── res/                             # 设置页 / 记录页资源
    └── src/io/github/cherry1jay/aicallagent/
        ├── MainHook.java                # ★ 适配核心：全部 hook 逻辑
        ├── Config.java                  # 配置读写（跨进程走 ContentProvider）
        ├── LlmClient.java               # OpenAI 兼容客户端 + 输出净化
        ├── Category.java                # 来电类型归类
        ├── Prompts.java                 # 6 类默认场景提示词
        ├── CallLogStore.java            # 通话记录存储
        ├── LogProvider.java             # 跨进程配置/日志通道
        ├── SettingsActivity.java        # 设置界面
        └── LogActivity.java             # 通话记录界面
```

---

## 与原版的源码差异

| 文件 | 改动 |
|---|---|
| `MainHook.java` | **完全重写**：hook 点换到 7.13 的类；新增字幕同步、号码获取、手动路径识别 |
| `LogProvider.java` | `CALLER_PKG`：`com.miui.voiceassist` → `com.xiaomi.aiasst.service` |
| `res/values/strings.xml` | `xposedscope` 改为 `com.xiaomi.aiasst.service` |
| `build.sh` | 路径转换补上 `/e/` `/f/` 等盘符 |
| 其余 | 沿用原版 |

---

## 免责声明

仅供个人学习与研究。通话录音、语音内容涉及他人隐私，各地法律对自动接听与录音的规定不同，请自行确保合法合规使用。因使用本模块产生的一切后果由使用者承担。

## License

MIT
