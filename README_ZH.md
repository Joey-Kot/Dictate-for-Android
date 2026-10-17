[English](README.md) | 简体中文

# Dictate

Dictate 是 Android 语音转写与选中文本后处理工具，通过不抢焦点的无障碍悬浮按钮操作。音频或选中文本直接发送到用户配置的指定服务，每次执行一个任务。转写和后处理共用同一个写入规则：实际写入时没有选区，就在当前光标处插入；有可编辑选区，就替换该段文本。

## 下载

| Platform | Download | SHA-256 |
|---|---|---|
| arm64-v8a | [arm64-v8a](https://github.com/Joey-Kot/Dictate/releases/download/Latest/Dictate-latest-arm64-v8a.apk) | [sha256](https://github.com/Joey-Kot/Dictate/releases/download/Latest/Dictate-latest-arm64-v8a.apk.sha256) |

## 操作演示与配置

| [<img src="demo/Demo-ZH.gif" alt="Dictate 中文操作演示" width="320">](demo/Demo-ZH.gif) |
| :---: |
| **实际操作**<br>通过悬浮按钮录音转写，再选中文本，使用自定义提示词进行改写、翻译等操作。 |

| [![权限设置](demo/1.%20Permissions.png)](demo/1.%20Permissions.png) | [![界面语言](demo/2.%20UI%20language.png)](demo/2.%20UI%20language.png) | [![录音设置](demo/3.%20Audio%20Record%20Settings.png)](demo/3.%20Audio%20Record%20Settings.png) |
| :---: | :---: | :---: |
| **1. 权限设置**<br>启用无障碍服务并授予麦克风权限。 | **2. 界面语言**<br>支持英语、中文、日语、德语、法语和俄语，选择后保存生效。 | **3. 录音设置**<br>选择输出采样率、编码、容器和比特率。 |
| [![音频 API 设置](demo/4.%20Audio%20API%20Settings.png)](demo/4.%20Audio%20API%20Settings.png) | [![改写 API 设置](demo/5.%20Rewrite%20API%20Settings.png)](demo/5.%20Rewrite%20API%20Settings.png) | [![提示词编辑](demo/6.%20Prompt%20Edit.png)](demo/6.%20Prompt%20Edit.png) |
| **4. 音频 API 设置**<br>配置转写服务的 Base URL、API Key、模型及附加 JSON 参数，并通过真实转写请求测试连接。 | **5. 改写 API 设置**<br>配置文本后处理的服务商、Base URL、API Key 和模型，管理提示词顺序并测试连接。 | **6. 提示词编辑**<br>设置服务商、图标、标题、内容和附加 JSON；默认继承主 Provider，也可配置并测试独立 API。 |
| [![分片上传设置](demo/7.%20Segmented%20Upload%20Settings.png)](demo/7.%20Segmented%20Upload%20Settings.png) | [![重试设置](demo/8.%20Retry%20Settings.png)](demo/8.%20Retry%20Settings.png) | [![交互设置](demo/9.%20Interaction%20Settings.png)](demo/9.%20Interaction%20Settings.png) |
| **7. 分片上传设置**<br>启用基于停顿分析的分片上传，设置最大分片时长、最小停顿时长和并发识别工作流数。 | **8. 重试设置**<br>开启自动重试，设置最大重试次数和初始等待时间。 | **9. 交互设置**<br>设置是否始终复制结果到剪贴板，以及长按阈值和双击最大间隔。 |
| [![显示设置](demo/10.%20Display%20Settings.png)](demo/10.%20Display%20Settings.png) | [![配置保存与导入导出](demo/11.%20Configuration%20Saving%20and%20Import%26Export.png)](demo/11.%20Configuration%20Saving%20and%20Import%26Export.png) | [![高级音频 API 设置](demo/12.%20Advanced%20Audio%20API%20Settings.png)](demo/12.%20Advanced%20Audio%20API%20Settings.png) |
| **10. 显示设置**<br>调整悬浮按钮大小、不透明度和各状态颜色，并管理通知设置。 | **11. 配置保存与导入导出**<br>保存设置，通过 JSON 导入或导出配置，以及查看或清空诊断信息。 | **12. 高级自定义 Audio API Provider**<br>根据独立的用户需求和厂商资料生成并校验声明式 ASR Provider 工作流，提供类型化、条件显示、单选和多选控件，支持单请求、流式响应、异步轮询和 WebSocket 实时转写。 |
| [![生成工作流](demo/13.%20Generate%20a%20workflow%20based%20on%20the%20requirements%20and%20the%20vendor%27s%20API%20documentation.png)](demo/13.%20Generate%20a%20workflow%20based%20on%20the%20requirements%20and%20the%20vendor%27s%20API%20documentation.png) | [![工作流 JSON 与摘要](demo/14.%20Generated%20workflow%20JSON%20and%20summary.png)](demo/14.%20Generated%20workflow%20JSON%20and%20summary.png) | [![动态参数与密钥](demo/15.%20Configurable%20parameters%20dynamically%20generated%20according%20to%20user%20requirements.png)](demo/15.%20Configurable%20parameters%20dynamically%20generated%20according%20to%20user%20requirements.png) |
| **13. 生成工作流**<br>粘贴厂商文档请求示例，描述需要使用的参数，即可生成自定义 Audio API Provider 工作流。 | **14. 工作流 JSON 与摘要**<br>查看生成的 JSON 与摘要。 | **15. 动态参数与密钥**<br>校验后按工作流声明生成类型化控件；只有工作流需要时才显示远端音频。 |
| [![远端音频配置](demo/16.%20Configurable%20cloud%20storage%20or%20WebDAV.png)](demo/16.%20Configurable%20cloud%20storage%20or%20WebDAV.png) | [![工作流端点测试](demo/17.%20Successfully%20tested%20the%20workflow%20API.png)](demo/17.%20Successfully%20tested%20the%20workflow%20API.png) |  |
| **16. 远端音频配置**<br>音频需要通过公网 URL 或云 URI 投递时，可配置 WebDAV、S3-compatible 或阿里云 OSS。 | **17. 工作流端点测试**<br>使用当前工作流草稿及其声明的值和密钥测试真实端点。 |  |

## 音频转录功能介绍

- **录音转写**：非 IME，通过不抢焦点的悬浮按钮进行录音转写，支持暂停、取消和重新转录最近一次录音，可熄屏使用。
- **文本改写**：配置提示词及不同服务商、模型、参数，选择文本后长按悬浮按钮可选择调用。
- **自定义 API**：配置普通 OpenAI-compatible 风格音频 API 与不同服务商的 Rewrite 接口、模型、提示词和额外请求参数。
- **高级自定义 Audio API Provider**：根据独立的用户需求和厂商资料生成并校验声明式 ASR Provider 工作流，提供类型化、条件显示、单选和多选控件，支持单请求、流式响应、异步轮询和 WebSocket 实时转写。
- **分片上传**：可选地将较长的非实时 ASR 任务拆为本地独立批次。基于原始 PCM 振幅停顿划分而不删除静音，FFmpeg 顺序导出完整媒体文件，再有界并发执行识别工作流；支持普通 OpenAI-compatible 转写和高级非实时工作流，不用于实时转写或 Rewrite。
- **可见的 ASR 失败**：重试耗尽后的终态 ASR 请求失败会写入简短、脱敏的占位符。分片批次会在失败分片的原始位置保留占位符，并保留其他成功分片的文本；取消、配置错误和本地媒体错误仍按普通错误处理。
- **自动写入**：转写与改写结果写入当前光标处或替换当前可编辑选区，默认保留结果的剪贴板副本，关闭后只有明确写入失败才使用剪贴板兜底。
- **音频处理**：选择录制和输出格式；内嵌 FFmpeg，无需另行安装。
- **本地并发分片上传**：基于音频振幅静音分析，以停顿作为优先断点并保留完整音频时间轴，将音频导出为独立媒体分片并受限并发提交至 ASR API。
- **任务与配置安全**：转码、网络请求和重试均可取消；API Key、工作流及远端存储密钥通过 Android Keystore 加密，并支持脱敏诊断、真实端点测试和已校验的 JSON 导入导出。

### 选中文本后处理功能介绍

- **选区操作菜单**：选中文本后长按空闲悬浮按钮，展开提示词列表，点击条目即可执行；没有选中文本或尚未保存提示词时，长按继续重发上一条录音。
- **独立服务配置**：在「改写 API 设置」中单独配置 Provider、Base URL、API Key 和 Model。内置 22 个 Rewrite Provider；请求格式、端点补全规则和认证方式见[后处理配置](#后处理配置)。
- **每条提示词独立选择 Provider**：默认「与主 Provider 相同」（Same as main provider），继承整套改写 API 设置；也可设置独立 Provider、Base URL、API Key 和 Model，并在保存前测试连接。
- **自定义提示词与图标**：每项包含图标、标题、实际提示词及独立附加 JSON，支持新增、编辑、删除和排序。图标可使用 8 个内置选项，也可导入 SVG、PNG、JPG。
- **按提示词覆盖模型和参数**：各条目的附加 JSON 可覆盖所选 API 的 Model 及其他请求字段；嵌套对象逐层合并，数组整体替换，其他值直接覆盖，显式为 `null` 的对象字段在请求中删除。
- **按需处理文本**：通过自己编写的提示词，实现润色、改写、翻译、总结等操作；这些用途由用户配置，可处理已有文本或转写后的文本，无需先开始新的录音。
- **共用写入与任务控制**：后处理与转写共用处理中状态、双击取消及自动重试。结果按实际写入时的焦点和选区插入或替换；后处理完成、失败或取消均不替换上一条录音。

### 悬浮按钮操作

| 状态 | 单击 | 长按 | 快速双击 |
|---|---|---|---|
| 空闲 | 录制 | 有选中文本且提示词列表非空时展开菜单；其他情况重发上一条录音 | 无操作 |
| 录制中 | 停止并转写 | 暂停 | 取消并丢弃 |
| 已暂停 | 无操作 | 恢复 | 取消并丢弃 |
| 处理中 | 无操作 | 无操作 | 取消当前任务，保留已有录音 |

手势优先级固定为 `拖拽 > 长按 > 双击 > 单击`。长按在抬起时确认，因此只要抬起前任何时刻的移动超过系统触摸阈值，本次触摸就只会被判为拖动。短按会等待配置的双击判定窗口后再确认，保证快速双击始终只有一个明确结果。

提示词菜单是可纵向滚动的圆角半透明面板，从按钮位置平滑展开；点击菜单外区域收回空闲麦克风按钮。点击条目后收回圆形按钮并进入处理中状态。新安装或尚未添加提示词时，即使选中了文本，长按也继续重发上一条录音。

## 使用要求

- Android 8.0+（`minSdk 26`）和 `arm64-v8a` 设备。
- 已启用的 Dictate 无障碍服务；录音还需要麦克风权限。
- BYOK!!! 需要自行配置 API 端点，无论是自己搭建的兼容服务还是第三方厂商。

## 从源码构建

详情请参考 [AGENTS.md](AGENTS.md)。

## 使用方法

1. 授予麦克风权限，在 Android 无障碍设置中启用 Dictate。通知可通过「显示设置」中的开关开启或关闭。
2. 普通模式填写 Base URL（`https://example.com` 或 `https://example.com/v1`）、API Key 和模型；也可启用「高级音频 API 设置」，生成或粘贴工作流，再填写声明的选项值和密钥。
3. 普通模式可选填写完整附加 JSON 对象，按合并规则生成 multipart 参数；对象和数组序列化为 JSON 字段值。`model` 可以覆盖，`file` 属于二进制音频附件，同名附加参数会报告冲突；高级音频 API 模式可在生成工作流时填写对应的要求。
5. 可选配置悬浮按钮大小、不透明度，以及预置或 Custom 三状态色系；保存配置后，在普通可编辑输入框中保留光标并使用悬浮按钮。

### 高级音频 API 设置

高级 Audio API 是可选的、受限的声明式 ASR 工作流，配置成功后，将生成一个自定义的 ASR Provider。也可以使用 Windows 版生成后，复制工作流 JSON 到安卓应用中粘贴即可使用。

1. 在 Rewrite API 中，任意配置一个可用的 Provider，然后启用Advanced Audio API。
2. 在 Vendor docs / examples 中粘贴厂商 API 文档。
3. 在 Requirements / preferences 中填写自己的需求，如需要模型、热词、方言识别等可配置。
4. 点击 Generate Workflow 生成，如出现生成失败，则按照要求补充相关厂商 API 文档后重新生成。
5. 生成成功后，配置相关配置项。点击 Test Workflow，测试成功即意味着成功。

### 分片上传设置

在非真实时流式模型中，配置可选的分片上传，为有时长限制的接口准备。

- 严格切分：在最接近上限的合格停顿结束点进行切分，每个段都会导出为独立媒体文件，如果找不到任何停顿，则会进行硬切。
- 不做修改：除了必要的切分截断外，原始时间轴上的语音不会做修改。

### 音频编码设置

录音保持 16-bit 单声道 PCM，采样率由当前输入路由决定。默认为 MP3、自动采样率和 128 kbps。

编码时由 FFmpeg 转换采样格式、位深度并重采样。目前所有可选编码器都接受单声道；如果编码器要求更多声道，转换流程会选择其支持的最小声道布局，将原始单声道复制到每个声道。

| 编码选项 | 输出容器 | 采样率／位深度限制 |
|---|---|---|
| Opus | OPUS、OGG、WEBM、MP4、MKV、MKA | 8 / 12 / 16 / 24 / 48 kHz |
| MP3 | MP3、WAV、AVI、MKV、MKA、MPEG；满足条件时提供 MP4/FLV | 8～48 kHz 的预设；容器限制见下文 |
| AAC | M4A、MP4、AAC（ADTS）、FLV、MKV、MKA、MOV | 最高 96 kHz；AAC/WAV 无法可靠完成编码后解码，因此不提供 |
| Vorbis | OGG、WEBM、MKV、MKA | 8～48 kHz 的预设 |
| FLAC | FLAC、OGG、MKV、MKA | 16 / 24-bit 输出 |
| ALAC | M4A、MP4、MOV | 16 / 24-bit 输出 |
| AC-3 | AC3、M4A、MP4、WAV、AVI、MKV、MKA、MPEG | 32 / 44.1 / 48 kHz |
| E-AC-3 | EAC3、MP4、MKV、MKA | 32 / 44.1 / 48 kHz |
| MP2 | WAV、MP4、MPEG | 16 / 22.05 / 24 / 32 / 44.1 / 48 kHz |
| ADPCM（MS） | WAV | 从 PCM16 转换 |
| AMR-NB | AMR、WAV | 8 kHz；精确的 4.75～12.2 kbps 档位 |
| AMR-WB | AMR | 16 kHz；精确的 6.6～23.85 kbps 档位 |
| Speex | SPX、OGG | 8 / 16 / 32 kHz；采用对应采样率的实际码率档位 |
| WavPack | WV | 16 / 24 / 32-bit 输出 |
| WMA v1 / v2 | WMA、ASF | 8～48 kHz 的预设 |
| PCM | WAV；16/24/32-bit 可用 MP4、MOV 及对应的 S16LE/S24LE/S32LE 裸流；16-bit 另可用 AVI | 8 / 16 / 24 / 32-bit；无符号 8-bit 使用 WAV |
| PCM 8-bit（有符号） | AIFF、S8 | 固定有符号 8-bit |
| PCM A-law / μ-law | WAV、对应的 ALAW/MULAW 裸流 | 固定压扩格式 |
| PCM Float 32/64-bit（LE） | WAV、MP4、对应的 F32LE/F64LE 裸流 | 固定浮点格式 |
| PCM 64-bit（LE） | WAV | 固定有符号 64-bit |
| PCM 16/24/32-bit（BE） | AIFF、MP4、对应的 S16BE/S24BE/S32BE 裸流 | 固定大端整数格式 |
| PCM Float 32/64-bit（BE） | AIFF、MP4、对应的 F32BE/F64BE 裸流 | 固定大端浮点格式 |

上表表示本地输出能力，所配置的转写服务还需接受对应编码、容器和采样率。裸 PCM 没有格式头，接收方需要获知匹配的采样格式、采样率和声道数；可使用目标设置执行端点测试，确认服务兼容性。

### 后处理配置

公共配置依次为 Provider、Base URL、API Key、Model，下方是「新增提示词」、已保存条目和「测试连接」。内置 22 个 Rewrite Provider。提示词放入对应 Provider 的系统／开发者级指令位置，选中文本作为用户输入。

| Provider | 配置 ID | 由 Base URL 生成的请求格式与端点 | API Key 字段中凭据的发送方式 |
|---|---|---|---|
| Custom / OpenAI-Compatible | `custom_openai_compatible` | Chat Completions；Base URL 只有域名时为 `/v1/chat/completions` | Bearer token |
| OpenAI / Responses | `openai_responses` | Responses；`/v1/responses` | Bearer token |
| OpenAI / Chat Completions | `openai_chat_completions` | Chat Completions；`/v1/chat/completions` | Bearer token |
| Azure OpenAI / Responses | `azure_openai_responses` | Responses；`/openai/v1/responses` | `api-key` |
| Azure OpenAI / Chat Completions | `azure_openai_chat_completions` | Chat Completions；`/openai/v1/chat/completions` | `api-key` |
| Anthropic / Claude | `anthropic_claude` | Messages；`/v1/messages` | `x-api-key` 与 `anthropic-version: 2023-06-01` |
| Google / Gemini | `google_gemini` | Gemini `generateContent`；`/v1beta/models/{model}:generateContent` | `x-goog-api-key` |
| Google / Vertex AI | `google_vertex_ai` | Gemini `generateContent`；Base URL 必须包含 `/v1/projects/{project}/locations/{location}/publishers/google`，再补全 `/models/{model}:generateContent` | OAuth Bearer access token |
| xAI / Grok | `xai_grok` | Chat Completions；`/v1/chat/completions` | Bearer token |
| Alibaba / Qwen Text | `alibaba_qwen_text` | 原生 DashScope Text Generation；`/api/v1/services/aigc/text-generation/generation` | Bearer token；`X-DashScope-SSE: enable` |
| Alibaba / Qwen Multimodal | `alibaba_qwen_multimodal` | 原生 DashScope Multimodal Generation；`/api/v1/services/aigc/multimodal-generation/generation` | Bearer token；`X-DashScope-SSE: enable` |
| Moonshot AI / Kimi | `moonshot_ai_kimi` | Chat Completions；`/v1/chat/completions` | Bearer token |
| Z.AI / GLM | `z_ai_glm` | Chat Completions；`/api/paas/v4/chat/completions` | Bearer token |
| DeepSeek / DeepSeek | `deepseek_deepseek` | Chat Completions；`/chat/completions` | Bearer token |
| Volcengine / Ark / Chat Completions | `volcengine_ark_chat_completions` | Chat Completions；`/api/v3/chat/completions` | Bearer token |
| Volcengine / Ark / Responses | `volcengine_ark_responses` | Responses；`/api/v3/responses` | Bearer token |
| Baidu / Qianfan | `baidu_qianfan` | Chat Completions；`/v2/chat/completions` | Bearer token |
| Tencent / TokenHub | `tencent_tokenhub` | Chat Completions；`/v1/chat/completions` | Bearer token |
| MiniMax / ChatCompletion V2 | `minimax_chat_completion_v2` | 原生 ChatCompletion V2；`/v1/text/chatcompletion_v2` | Bearer token |
| Mistral AI / Mistral | `mistral_ai_mistral` | Chat Completions；`/v1/chat/completions` | Bearer token |
| Groq / Cloud | `groq_cloud` | Chat Completions；`/openai/v1/chat/completions` | Bearer token |
| Cohere / Compatibility API | `cohere_compatibility_api` | OpenAI Compatibility Chat Completions；`/compatibility/v1/chat/completions` | Bearer token |

点击「新增提示词」或已有条目，在弹窗内配置 Provider、图标、标题、提示词内容和附加 JSON。保存时校验标题、内容和附加 JSON。支持使用上下箭头调整列表菜单顺序。

默认选择「与主 Provider 相同」（Same as main provider）。这里的「主」指「改写 API 设置」中的公共配置，整套继承 Provider、Base URL、API Key 和 Model。

选择具体 Provider 后完全使用该条目的 API 字段，即使它与主 Provider 相同，空字段也不会借用公共值。提示词编辑弹窗会显示「测试连接」。使用尚未保存的 Provider、Base URL、API Key 和 Model，以及固定的最小指令和输入进行连接测试，忽略条目的标题、提示词正文与附加 JSON。

图标提供 8 个内置选项，也可导入 SVG、PNG、JPG。自定义图标复制到应用私有目录，单个文件最多 1 MiB，总计最多 8 MiB；配置导出携带被引用的图标，缺失或无效资源回退到内置图标。

### 附加 JSON 合并

输入应为 JSON 对象，可留空。先生成基础请求，再应用附加 JSON：对象逐层合并，数组整体替换，其他值覆盖同层同名字段。附加 JSON 中对象字段的值为 `null` 时删除该字段，原来不存在也不创建；数组中的 `null` 元素仍是数组值。保存、编辑和导出保留删除指令，只在生成请求时执行。

例如某条提示词配置以下参数，将使用 `another-model`，并删除基础请求中的 `temperature`：

```json
{
  "model": "another-model",
  "temperature": null,
  "metadata": { "task": "summary" }
}
```

## 安全与隐私

- 录音、停顿分析和转码在本机完成。
- 作为 BYOK 程序，无论是音频还是 Rewrite 内容，都会发送到用户指定的端点，可以是自部署的开源服务，也可以是厂商。如非常在意隐私，将端点指向自部署服务即可。
- 生成高级工作流时，可选的用户需求和厂商资料会先在本地尽力脱敏，再作为相互独立的结构化字段发送给已配置的 Rewrite 服务。
- “始终复制到剪贴板”默认开启。关闭后恢复为仅兜底模式：只有当前焦点明确写入失败时才复制。无法确认写入结果时，不会自动重试或复制，因为文本仍可能已写入编辑器。
- 选区读取及无障碍写入依赖目标应用提供的能力。只读文本可以作为后处理输入；密码框、受保护界面和自绘控件可能不暴露选区或拒绝写入。无可写入焦点时沿用剪贴板兜底，项目不做逐 App 专项兼容。
- 导出配置文件时不会包含任何密钥。配置文件当中可以手动写入密钥相关的字段，但在导入时如包含密钥则需要手动点击确认，包括提示词中可选的 `apiKey` 和 `advancedAudio.secrets` 映射。
- 程序不会验证所配置 API 是否可信，请只使用你愿意发送录音或选中文本的服务。

## 许可证

`GPL-3.0-or-later`。详见 [LICENSE](LICENSE)、[NOTICE](NOTICE)、[THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。
