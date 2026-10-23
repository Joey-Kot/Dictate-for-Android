English | [简体中文](README_ZH.md)

# Dictate

Dictate is an Android speech-transcription and selected-text post-processing tool operated through a non-focus-stealing accessibility floating button. Audio and selected text are sent directly to endpoints configured by the user, with one task executed at a time. Transcription and post-processing share the same insertion rule: if there is no selection when the result is inserted, it is inserted at the current cursor; if there is an editable selection, that text is replaced.

## Download

| Platform | Download | SHA-256 |
|---|---|---|
| arm64-v8a | [arm64-v8a](https://github.com/Joey-Kot/Dictate/releases/download/Latest/Dictate-latest-arm64-v8a.apk) | [sha256](https://github.com/Joey-Kot/Dictate/releases/download/Latest/Dictate-latest-arm64-v8a.apk.sha256) |

## Demonstration and configuration

| [<img src="demo/Demo-ZH.gif" alt="Dictate Chinese usage demo" width="320">](demo/Demo-ZH.gif) |
| :---: |
| **Usage**<br>Record and transcribe with the floating button, then select text and use custom prompts to rewrite, translate, and perform other actions. |

| [![Permissions](demo/1.%20Permissions.png)](demo/1.%20Permissions.png) | [![UI language](demo/2.%20UI%20language.png)](demo/2.%20UI%20language.png) | [![Audio recording settings](demo/3.%20Audio%20Record%20Settings.png)](demo/3.%20Audio%20Record%20Settings.png) |
| :---: | :---: | :---: |
| **1. Permissions**<br>Enable the accessibility service and grant microphone permission. | **2. UI language**<br>English, Chinese, Japanese, German, French, and Russian are supported; the selected language takes effect after saving. | **3. Recording settings**<br>Choose the output sample rate, codec, container, and bitrate. |
| [![Audio API settings](demo/4.%20Audio%20API%20Settings.png)](demo/4.%20Audio%20API%20Settings.png) | [![Rewrite API settings](demo/5.%20Rewrite%20API%20Settings.png)](demo/5.%20Rewrite%20API%20Settings.png) | [![Prompt editor](demo/6.%20Prompt%20Edit.png)](demo/6.%20Prompt%20Edit.png) |
| **4. Audio API settings**<br>Configure the transcription service's Base URL, API Key, model, and extra JSON parameters, then test connectivity with a real transcription request. | **5. Rewrite API settings**<br>Configure the provider, Base URL, API Key, and model for text post-processing; manage prompt order and test connectivity. | **6. Prompt editing**<br>Set the provider, icon, title, content, and extra JSON. Prompts inherit the main provider by default, but can use and test an independent API. |
| [![Segmented upload settings](demo/7.%20Segmented%20Upload%20Settings.png)](demo/7.%20Segmented%20Upload%20Settings.png) | [![Retry settings](demo/8.%20Retry%20Settings.png)](demo/8.%20Retry%20Settings.png) | [![Interaction settings](demo/9.%20Interaction%20Settings.png)](demo/9.%20Interaction%20Settings.png) |
| **7. Segmented upload settings**<br>Enable pause-analysis-based segmented uploads and set the maximum segment duration, minimum pause duration, and concurrent recognition-workflow limit. | **8. Retry settings**<br>Enable automatic retries and set the maximum retries and initial wait time. | **9. Interaction settings**<br>Choose whether results are always copied to the clipboard, and set the long-press threshold and maximum double-tap interval. |
| [![Display settings](demo/10.%20Display%20Settings.png)](demo/10.%20Display%20Settings.png) | [![Saving and importing or exporting configuration](demo/11.%20Configuration%20Saving%20and%20Import%26Export.png)](demo/11.%20Configuration%20Saving%20and%20Import%26Export.png) | [![Advanced Audio API settings](demo/12.%20Advanced%20Audio%20API%20Settings.png)](demo/12.%20Advanced%20Audio%20API%20Settings.png) |
| **10. Display settings**<br>Adjust the floating-button size, opacity, and colors for each state, and manage notification settings. | **11. Saving and importing or exporting configuration**<br>Save settings, import or export configuration as JSON, and view or clear diagnostics. | **12. Advanced custom Audio API provider**<br>Generate and validate declarative ASR-provider workflows from separate user requirements and vendor documentation. Workflows offer typed, conditionally visible, single-select, and multi-select controls, and support single requests, streamed responses, asynchronous polling, and real-time WebSocket transcription. |
| [![Generate a workflow from requirements and vendor API documentation](demo/13.%20Generate%20a%20workflow%20based%20on%20the%20requirements%20and%20the%20vendor%27s%20API%20documentation.png)](demo/13.%20Generate%20a%20workflow%20based%20on%20the%20requirements%20and%20the%20vendor%27s%20API%20documentation.png) | [![Generated workflow JSON and summary](demo/14.%20Generated%20workflow%20JSON%20and%20summary.png)](demo/14.%20Generated%20workflow%20JSON%20and%20summary.png) | [![Dynamically generated parameters and secrets](demo/15.%20Configurable%20parameters%20dynamically%20generated%20according%20to%20user%20requirements.png)](demo/15.%20Configurable%20parameters%20dynamically%20generated%20according%20to%20user%20requirements.png) |
| **13. Generate workflow**<br>Paste vendor documentation or request examples and describe the parameters you need to generate a custom Audio API provider workflow. | **14. Workflow JSON and summary**<br>View the generated JSON and its summary. | **15. Dynamic parameters and secrets**<br>After validation, typed controls are generated from workflow declarations; remote audio appears only when the workflow needs it. |
| [![Remote audio configuration](demo/16.%20Configurable%20cloud%20storage%20or%20WebDAV.png)](demo/16.%20Configurable%20cloud%20storage%20or%20WebDAV.png) | [![Successful workflow endpoint test](demo/17.%20Successfully%20tested%20the%20workflow%20API.png)](demo/17.%20Successfully%20tested%20the%20workflow%20API.png) |  |
| **16. Remote audio configuration**<br>When audio must be delivered through a public URL or cloud URI, configure WebDAV, S3-compatible storage, or Alibaba Cloud OSS. | **17. Workflow endpoint test**<br>Test the real endpoint using the current workflow draft and its declared values and secrets. |  |

## Speech-transcription features

- **Recording and transcription**: A non-IME floating button records and transcribes audio without taking focus. It supports pause, cancel, retrying the most recent recording, and use with the screen off.
- **Text rewriting**: Configure prompts with different providers, models, and parameters, then long-press the floating button after selecting text to choose an action.
- **Custom APIs**: Configure a regular OpenAI-compatible-style audio API plus provider-specific Rewrite APIs, models, prompts, and extra request parameters.
- **Advanced custom Audio API providers**: Generate and validate declarative ASR-provider workflows from separate user requirements and vendor documentation. Workflows provide typed, conditionally visible, single-select, and multi-select controls, and support single requests, streamed responses, asynchronous polling, and real-time WebSocket transcription.
- **Segmented uploads**: Optionally split long non-real-time ASR tasks into independent local batches. Segments are based on pauses in raw PCM amplitude without removing silence; FFmpeg exports complete media files sequentially, then recognition workflows run with bounded concurrency. This supports regular OpenAI-compatible transcription and Advanced non-real-time workflows, but not real-time transcription or Rewrite.
- **Visible ASR failures**: ASR request failures after all retries are exhausted are logged as brief, redacted placeholders. No placeholder is logged if the transcription itself is empty, meaning no valid characters were recognized.
- **Automatic insertion**: Transcription and rewrite results are inserted at the current cursor or replace the current editable selection. A clipboard copy of the result is kept by default; when disabled, the clipboard is used only as a fallback after a confirmed insertion failure.
- **Audio processing**: Select recording and output formats; embedded FFmpeg requires no separate installation.
- **Local concurrent segmented uploads**: Analyze silence from audio amplitude, prefer pauses as boundaries while preserving the full audio timeline, export independent media segments, and submit them to the ASR API with bounded concurrency.
- **Task and configuration safety**: Transcoding, network requests, and retries are cancellable. API keys, workflows, and remote-storage secrets are encrypted through Android Keystore, with redacted diagnostics, real endpoint tests, and validated JSON import/export.

### Selected-text post-processing features

- **Selection action menu**: Long-press the idle floating button after selecting text to expand the prompt list, then tap an item to run it. Without selected text or saved prompts, long-press continues to resubmit the most recent recording.
- **Independent service configuration**: Configure Provider, Base URL, API Key, and Model separately in **Rewrite API Settings**. There are 25 built-in Rewrite providers; see [Post-processing configuration](#post-processing-configuration) for request formats, endpoint completion, and authentication.
- **Per-prompt provider selection**: **Same as Main Provider** is the default and inherits the complete Rewrite API configuration. A prompt can also use its own Provider, Base URL, API Key, and Model, and test the connection before saving.
- **Custom prompts and icons**: Each item has an icon, title, actual prompt, and independent extra JSON. Items can be added, edited, deleted, and reordered. 25 built-in two-tone blue icons are available, and SVG, PNG, and JPG can be imported.
- **Per-prompt model and parameter overrides**: A prompt's extra JSON can override the selected API's Model and other request fields. Nested objects merge recursively, arrays are replaced as a whole, other values override directly, and object members explicitly set to `null` are deleted from the request.
- **On-demand text processing**: Use your own prompts for polishing, rewriting, translation, summarization, and more. These user-configured actions work on existing text or transcription results without starting a new recording.
- **Shared insertion and task control**: Post-processing and transcription share the processing state, double-tap cancellation, and automatic retry. Results are inserted or replace text using the focus and selection that exist when insertion occurs; post-processing completion, failure, or cancellation never replaces the previous recording.

### Floating-button controls

| State | Tap | Long press | Quick double tap |
|---|---|---|---|
| Idle | Record | Open the menu when text is selected and the prompt list is non-empty; otherwise resubmit the most recent recording | No action |
| Recording | Stop and transcribe | Pause | Cancel and discard |
| Paused | No action | Resume | Cancel and discard |
| Processing | No action | No action | Cancel the current task and keep the existing recording |

Gesture priority is fixed: `drag > long press > double tap > tap`. A long press is confirmed on release, so if movement at any point before release exceeds the system touch threshold, the gesture is treated only as a drag. A short tap waits for the configured double-tap window before it is confirmed, ensuring a quick double tap always has one unambiguous outcome.

The prompt menu is a vertically scrollable, rounded, translucent panel that expands smoothly from the button position. Tapping outside it restores the idle microphone button. Tapping an item collapses the circular button and enters processing. On a new installation or before prompts are added, long-press continues to resubmit the most recent recording even when text is selected.

## Requirements

- Android 8.0+ (`minSdk 26`) on an `arm64-v8a` device.
- The Dictate accessibility service must be enabled; recording also requires microphone permission.
- BYOK: configure your own API endpoint, whether it is a self-hosted compatible service or a third-party provider.

## Building from source

See [AGENTS.md](AGENTS.md) for details.

## Usage

1. Grant microphone permission and enable Dictate in Android Accessibility settings. Notifications can be enabled or disabled with the switch in **Display Settings**.
2. In regular mode, enter a Base URL (`https://example.com` or `https://example.com/v1`), API Key, and model. You can also enable **Advanced Audio API Settings**, generate or paste a workflow, then fill in its declared option values and secrets.
3. In regular mode, optionally enter a complete extra JSON object to produce multipart parameters according to the merge rules; objects and arrays are serialized as JSON field values. `model` can be overridden, while `file` is the binary audio attachment and an extra parameter with that name reports a conflict. In Advanced Audio API mode, specify the relevant requirements while generating the workflow.
4. Optionally configure the floating-button size, opacity, and built-in or custom three-state colors. After saving, keep a cursor in a regular editable input and use the floating button.

### Advanced Audio API settings

Advanced Audio API is an optional, constrained declarative ASR workflow. Once configured successfully, it creates a custom ASR provider. You can also generate a workflow with the Windows version, then copy and paste its workflow JSON into the Android application.

1. On Rewrite API, configure any usable provider, then enable Advanced Audio API.
2. Paste the vendor API documentation into Vendor docs / examples.
3. In Requirements / preferences, specify your needs, such as configurable models, hotwords, or dialect recognition.
4. Click Generate Workflow. If generation fails, add the requested vendor API documentation and generate again.
5. After generation succeeds, configure the generated fields. A successful Test Workflow means the workflow is working.

### Segmented upload settings

For non-real-time streaming models, configure optional segmented uploads for APIs with duration limits.

- Strict segmentation: Split at the valid pause ending closest to the limit. Each segment is exported as an independent media file; if no pause exists, a hard cut is used.
- No modification: Other than the required split truncation, speech on the original timeline is not modified.

### Audio encoding settings

Recording remains 16-bit mono PCM, and its sample rate is determined by the current input route. The default output is MP3 with automatic sample rate and 128 kbps.

FFmpeg converts the sample format and bit depth and resamples during encoding. All currently selectable encoders accept mono audio. If an encoder requires more channels, conversion chooses the minimum supported channel layout and copies the original mono channel to every channel.

| Encoding option | Output containers | Sample-rate / bit-depth limits |
|---|---|---|
| Opus | OPUS, OGG, WEBM, MP4, MKV, MKA | 8 / 12 / 16 / 24 / 48 kHz |
| MP3 | MP3, WAV, AVI, MKV, MKA, MPEG; MP4/FLV where supported | Presets from 8 to 48 kHz; container restrictions apply below |
| AAC | M4A, MP4, AAC (ADTS), FLV, MKV, MKA, MOV | Up to 96 kHz; AAC/WAV is not offered because encode-then-decode cannot be completed reliably |
| Vorbis | OGG, WEBM, MKV, MKA | Presets from 8 to 48 kHz |
| FLAC | FLAC, OGG, MKV, MKA | 16 / 24-bit output |
| ALAC | M4A, MP4, MOV | 16 / 24-bit output |
| AC-3 | AC3, M4A, MP4, WAV, AVI, MKV, MKA, MPEG | 32 / 44.1 / 48 kHz |
| E-AC-3 | EAC3, MP4, MKV, MKA | 32 / 44.1 / 48 kHz |
| MP2 | WAV, MP4, MPEG | 16 / 22.05 / 24 / 32 / 44.1 / 48 kHz |
| ADPCM (MS) | WAV | Converted from PCM16 |
| AMR-NB | AMR, WAV | 8 kHz; exact 4.75–12.2 kbps modes |
| AMR-WB | AMR | 16 kHz; exact 6.6–23.85 kbps modes |
| Speex | SPX, OGG | 8 / 16 / 32 kHz; actual bitrate modes for the matching sample rate |
| WavPack | WV | 16 / 24 / 32-bit output |
| WMA v1 / v2 | WMA, ASF | Presets from 8 to 48 kHz |
| PCM | WAV; MP4, MOV, and matching S16LE/S24LE/S32LE raw streams for 16/24/32-bit; AVI is also available for 16-bit | 8 / 16 / 24 / 32-bit; unsigned 8-bit uses WAV |
| PCM 8-bit (signed) | AIFF, S8 | Fixed signed 8-bit |
| PCM A-law / μ-law | WAV, matching ALAW/MULAW raw streams | Fixed companded format |
| PCM Float 32/64-bit (LE) | WAV, MP4, matching F32LE/F64LE raw streams | Fixed floating-point format |
| PCM 64-bit (LE) | WAV | Fixed signed 64-bit |
| PCM 16/24/32-bit (BE) | AIFF, MP4, matching S16BE/S24BE/S32BE raw streams | Fixed big-endian integer format |
| PCM Float 32/64-bit (BE) | AIFF, MP4, matching F32BE/F64BE raw streams | Fixed big-endian floating-point format |

The table describes local output capability. The configured transcription service must also accept the selected codec, container, and sample rate. Bare PCM has no format header, so the receiver must know the matching sample format, sample rate, and channel count. Use endpoint testing with the target settings to confirm service compatibility.

### Post-processing configuration

The shared configuration is Provider, Base URL, API Key, and Model, followed by **Add Prompt**, saved entries, and **Test connectivity**. There are 25 built-in Rewrite providers. Prompts are placed in the system/developer-level instruction position for the corresponding provider, while selected text is sent as user input.

| Provider | Configuration ID | Request format and endpoint built from Base URL | How the credential from API Key is sent |
|---|---|---|---|
| Custom / OpenAI-Compatible | `custom_openai_compatible` | Chat Completions; `/v1/chat/completions` when Base URL has only a domain | Bearer token |
| OpenAI / Responses | `openai_responses` | Responses; `/v1/responses` | Bearer token |
| OpenAI / Chat Completions | `openai_chat_completions` | Chat Completions; `/v1/chat/completions` | Bearer token |
| Azure OpenAI / Responses | `azure_openai_responses` | Responses; `/openai/v1/responses` | `api-key` |
| Azure OpenAI / Chat Completions | `azure_openai_chat_completions` | Chat Completions; `/openai/v1/chat/completions` | `api-key` |
| Anthropic / Claude | `anthropic_claude` | Messages; `/v1/messages` | `x-api-key` plus `anthropic-version: 2023-06-01` |
| Google / Gemini | `google_gemini` | Gemini `generateContent`; `/v1beta/models/{model}:generateContent` | `x-goog-api-key` |
| Google / Vertex AI | `google_vertex_ai` | Gemini `generateContent`; Base URL must include `/v1/projects/{project}/locations/{location}/publishers/google`, then `/models/{model}:generateContent` is added | OAuth Bearer access token |
| xAI Grok / Responses | `xai_grok_responses` | Responses; `/v1/responses` | Bearer token |
| xAI Grok / Chat Completions | `xai_grok_chat_completions` | Chat Completions; `/v1/chat/completions` | Bearer token |
| Alibaba / Qwen Text | `alibaba_qwen_text` | Native DashScope Text Generation; `/api/v1/services/aigc/text-generation/generation` | Bearer token; `X-DashScope-SSE: enable` |
| Alibaba / Qwen Multimodal | `alibaba_qwen_multimodal` | Native DashScope Multimodal Generation; `/api/v1/services/aigc/multimodal-generation/generation` | Bearer token; `X-DashScope-SSE: enable` |
| Moonshot Kimi / Responses | `moonshot_kimi_responses` | Responses; `/v1/responses` | Bearer token |
| Moonshot Kimi / Chat Completions | `moonshot_kimi_chat_completions` | Chat Completions; `/v1/chat/completions` | Bearer token |
| Z.AI / GLM | `z_ai_glm` | Chat Completions; `/api/paas/v4/chat/completions` | Bearer token |
| DeepSeek / Responses | `deepseek_responses` | Responses; `/responses` | Bearer token |
| DeepSeek / Chat Completions | `deepseek_chat_completions` | Chat Completions; `/chat/completions` | Bearer token |
| Volcengine / Ark / Chat Completions | `volcengine_ark_chat_completions` | Chat Completions; `/api/v3/chat/completions` | Bearer token |
| Volcengine / Ark / Responses | `volcengine_ark_responses` | Responses; `/api/v3/responses` | Bearer token |
| Baidu / Qianfan | `baidu_qianfan` | Chat Completions; `/v2/chat/completions` | Bearer token |
| Tencent / TokenHub | `tencent_tokenhub` | Chat Completions; `/v1/chat/completions` | Bearer token |
| MiniMax / ChatCompletion V2 | `minimax_chat_completion_v2` | Native ChatCompletion V2; `/v1/text/chatcompletion_v2` | Bearer token |
| Mistral AI / Mistral | `mistral_ai_mistral` | Chat Completions; `/v1/chat/completions` | Bearer token |
| Groq / Cloud | `groq_cloud` | Chat Completions; `/openai/v1/chat/completions` | Bearer token |
| Cohere / Compatibility API | `cohere_compatibility_api` | OpenAI Compatibility Chat Completions; `/compatibility/v1/chat/completions` | Bearer token |

Tap **Add Prompt** or an existing entry to configure its Provider, icon, title, prompt content, and extra JSON in the dialog. Title, content, and extra JSON are validated on save. Use the up and down arrows to reorder the list menu.

**Same as Main Provider** is the default. “Main” means the shared configuration in **Rewrite API Settings**: Provider, Base URL, API Key, and Model as a complete set.

Once a specific Provider is selected, the prompt uses its own API fields completely. Even if it is the same provider as the main one, empty fields do not fall back to shared values. The prompt-editing dialog shows **Test connectivity**, which uses the unsaved Provider, Base URL, API Key, and Model with fixed minimal instructions and input, ignoring the entry's title, prompt body, and extra JSON.

25 built-in two-tone blue icons are available, and SVG, PNG, and JPG can be imported. Custom icons are copied into the application's private directory, with a 1 MiB maximum per file and 8 MiB total. Exported configuration carries referenced icons; missing or invalid assets fall back to a built-in icon.

### Extra JSON merging

The input must be a JSON object and may be empty. A base request is built first, then extra JSON is applied: objects merge recursively, arrays replace as a whole, and other values override fields with the same name at the same level. An object member set to `null` in extra JSON deletes that field, and does not create it if it did not previously exist; `null` array elements remain array values. Saving, editing, and export retain deletion directives, which are applied only when building a request.

For example, configuring a prompt with the following parameters uses `another-model` and deletes `temperature` from the base request:

```json
{
  "model": "another-model",
  "temperature": null,
  "metadata": { "task": "summary" }
}
```

## Security and privacy

- Recording, pause analysis, and transcoding happen locally.
- As a BYOK application, audio and Rewrite content are sent to the endpoint you configure. It can be a self-hosted open-source service or a vendor service. If privacy is especially important, point the endpoint to a self-hosted service.
- When generating an Advanced workflow, optional user requirements and vendor documentation are best-effort redacted locally before being sent as separate structured fields to the configured Rewrite service.
- **Always copy to clipboard** is enabled by default. When disabled, it returns to fallback-only behavior: results are copied only after a confirmed failure to insert at the current focus. When insertion cannot be confirmed, Dictate neither retries nor copies automatically, because text may already have reached the editor.
- Selection reading and accessibility insertion depend on capabilities exposed by the target application. Read-only text can be post-processing input; password fields, protected interfaces, and custom-drawn controls may not expose a selection or may reject insertion. Without a writable focus, the clipboard fallback applies. The project does not provide app-specific compatibility handling.
- Exported configuration files never include secrets. Secret-related fields can be written manually into a configuration file, but imports containing secrets require manual confirmation, including an optional prompt `apiKey` and the `advancedAudio.secrets` mapping.
- The application does not verify whether a configured API is trustworthy. Use only services to which you are willing to send recordings or selected text.

## License

`GPL-3.0-or-later`. See [LICENSE](LICENSE), [NOTICE](NOTICE), and [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
