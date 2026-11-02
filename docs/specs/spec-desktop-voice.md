# Spec: local voice for the desktop app

Status: **research for later, nothing built.** No model was downloaded or run for this. Looked up
2026-11-01; this field moves fast, so check the versions again when the desktop work starts.

The owner's idea, in his words:

> "when we build the desktop version we probably need some kind of local voice model, have an agent
> look online to figure out what's some of the best voice models that are accurate but fast,
> CPU-based."

> "you wouldn't download this as part of the package, but if you want voice, some kind of setting in
> the desktop says hey, if you enable voice you can download X model, maybe even have choices of
> models you can download. I don't want to go too far with it. I think Windows even has its own voice
> thing built in possibly. Let's just have the agent research this and then add it to our notes
> locally here, so we reference this idea when we start building the desktop app."

The bar is the phone's (`spec-voice.md`): you talk, and your words are in the box before you've
finished looking down. The rule here is new: no cloud, audio stays on the machine. That rules out the
Soniox route for this client.

**How far to trust this.** Each number links to where it came from. Pages were read through a
summarising fetch tool, not line by line, and items marked *(search summary)* were seen only in a search
result. Check a figure at its source before deciding on it. "Inferred" means my reasoning, not something
a source says. "Not found" means I looked and didn't find it.

---

## 1. Recommendation in one place

| # | Point |
|---|---|
| R1 | **Runtime: sherpa-onnx.** It's the only candidate I found with published JVM jars for Linux, Mac and Windows ([jar sizes][sh-rel], [Gradle setup][sh-java]), it's Apache-2.0 ([repo][sh-repo]), and it runs every model below, so swapping models is a config change. |
| R2 | **Try first: Parakeet TDT 0.6B v3, int8.** Best-evidenced CPU speed, punctuation and capitals built in ([card][pk3]), 25 languages including Russian, which the phone's Language setting already hints ([card][pk3], `DECISIONS.md`). **The catch: words land at each pause, not as you talk.** |
| R3 | **Test next to it, same harness: Nemotron Speech Streaming EN, 560 ms int8.** The live-words option on the same runtime. If phrase-at-a-pause feels wrong in R2, this becomes first choice. English only, thinner evidence. |
| R4 | **Fallback for weak machines: Whisper base.en**, through sherpa-onnx or whisper.cpp. Smallest download that still punctuates, and much less memory. Lower accuracy, and it needs a VAD to stop it inventing text in silence. |
| R5 | **Watch: Moonshine Streaming.** On paper the best fit for this exact job: live words, MIT, documented word boosting whose own example is "Kubernetes". It isn't first only because there's no JVM binding or Intel Mac build, and I couldn't establish whether it punctuates. |
| R6 | **Built-ins: don't build on them for the first version.** Windows' API for free dictation is cloud, Linux has none. macOS's newer API is good and on-device but needs a Swift helper; a possible later choice (section 3). |
| R7 | **VAD: yes, Silero**, for R2 and R4. Section 6. |

Why not the top of the leaderboard? Cohere Transcribe, Qwen3-ASR and Granite lead on accuracy but were
scored on a GPU, and I found no laptop CPU figures for them (section 4).

---

## 2. Comparison

Word error rate (WER) here is the Open ASR Leaderboard's average over eight English sets unless noted.
**Three things to know about it:** it strips punctuation and capitals before scoring, so it says nothing
about formatted dictation ([paper][oasr-paper]); none of its sets is people dictating code; and its speed
column is GPU only (A100 in the [paper][oasr-paper], H200 in the [current repo][oasr-repo], no CPU track),
so its speed figures are left out of this table. I couldn't read the live board (the [Space][oasr-space]
didn't render), so these come from copies.

| Model, runtime | WER | CPU speed: named CPU where one exists | Live words? | Download | RAM in use | Punctuation, capitals | Custom words | License | From the JVM |
|---|---|---|---|---|---|---|---|---|---|
| **Parakeet TDT 0.6B v3** int8, sherpa-onnx | [6.32][lam], [6.18][pinggy] later snapshot | see 2.1 | No, after a pause. [Streaming not offered][localai] for this model in parakeet.cpp; in sherpa-onnx it's [listed as offline][sh-nemo] | [465 MB][sh-assets] (about 640 MB [unpacked][sh-nemo]) | [1.16 to 1.65 GB peak][vidclean], other runtime; [at least 2 GB to load][pk3], NVIDIA | [Yes][pk3] | Beam search only, [fix not yet released](#vocab) | [CC-BY-4.0][pk3] | [Official jars][sh-rel] |
| **Parakeet TDT 0.6B v2** int8, sherpa-onnx | [6.05][bl], [6.02][pinggy] | [RTF 0.118 on a test clip, 0.028 on a long file, 2 threads][sh-nemo], CPU not named | No | [460 MB][sh-assets] | Not found; inferred like v3 | Not rechecked | As v3 | CC-BY-4.0 ([Pinggy][pinggy]) | Official jars |
| **Parakeet Unified EN 0.6B** int8, sherpa-onnx | [5.91 offline, 8.44 at 160 ms, 15.63 at 80 ms][pku] | [RTF 0.032, 2 threads, 7.4 s clip][sh-nemo], CPU not named | The model can; the [sherpa-onnx package is non-streaming][sh-nemo] | Not found; [631 MB on disk][ow-cmp] | Not found | [Yes][pku] | [Beam search rejected][sh-4131] | [NVIDIA Open Model][pku] | Official jars |
| **Nemotron EN 0.6B** int8, sherpa-onnx | [6.93 at 1.12 s, 7.07 at 0.56 s, 7.67 at 0.16 s, 8.43 at 0.08 s][nem], NVIDIA's own | [RTF 0.16, 1 thread, 6.6 s clip][sh-nemotron], CPU not named. [Keeps up on "a modern laptop CPU"][ow-stream], no numbers | **Yes.** ["Well under a second" behind][ow-stream], no measurement | Package size not found; [632 MB on disk][ow-stream] | Not found | [Card says yes][nem]; the [sherpa-onnx sample][sh-nemotron] shows capitals, no marks. Check | [Merged Sept 2026][sh-3895]; inferred to be in 2.0.x | [Card: NVIDIA Open Model][nem]; [OpenWhispr says OpenMDW][ow-cmp]. Check | Official jars |
| **Moonshine Medium Streaming**, its own library | [6.65][ms-models], its own; [6.66 on the board][bl] | [Latency 269 ms on Linux x86][ms-vs], CPU not named, vendor. [29% load on Apple M3][ms-paper] | **Yes.** [Updates every 0.5 s][ms-opt] by default | [About 230 MB][ms-dl] (my sum), [296 MB as Q8 GGUF][ms-gguf-m] | Not found | **Not found** | [Yes, documented][ms-dom] | [MIT][ms-lic] | **None.** [Android only][ms-qs]; write a shim over its C library |
| **Moonshine Small Streaming** | [7.84][ms-models] | [165 ms on Linux x86][ms-vs], vendor | Yes | [199 MB as Q8 GGUF][ms-gguf-s] | Not found | Not found | Yes | MIT | None |
| **Whisper base.en**, whisper.cpp or sherpa-onnx | Board average not found. [4.07 on LibriSpeech clean][yazses] (one laptop, 200 clips) | [RTF 0.283, i7-1370P][yazses] (faster-whisper int8) | No, or [re-run every 0.5 s][wcpp] with whisper-stream | [142 MiB][wcpp-m] ggml; [199 MB][sh-assets] sherpa package | [53 MB][yazses] vs [about 1 GB][lam] from OpenAI's table. They disagree | Yes (inferred from the model) | [Prompt, about 224 tokens][wcpp-prompt], whisper.cpp only. [Not in sherpa-onnx][sh-hot] | [MIT][wcpp] | [JNA binding, build from source][wcpp-java]; macOS struck through. Or sherpa jars |
| **Whisper small.en** | [8.59][ms-vs] (small, in Moonshine's table). [2.59 LibriSpeech clean][yazses] | [RTF 0.520, i7-1370P][yazses]; [0.13 to 0.16, i7-12700K, 8 threads][fw] (my calc) | As above | [466 MiB][wcpp-m]; [606 MB][sh-assets] sherpa package | [520 MB][yazses] | Yes (inferred) | As above | MIT | As above |
| **faster-whisper** | Same as Whisper | See 2.1 | [No native streaming][fw] | Same as Whisper | [1,477 MB, small int8][fw] | Yes (inferred) | Not on its README | [MIT][fw] | **Needs [Python 3.9+][fw]**: a bundled process. Last release [Oct 2025][fw-rel] |
| **Canary 180M Flash** | [7.12][canary180] | [GPU only][canary180]; CPU not found | [Not mentioned][canary180] | Not found | Not found | [Optional][canary180] | Not found | [CC-BY-4.0][canary180] | Not checked in sherpa-onnx |
| **Vosk** big and small | Own figures, LibriSpeech clean only: [5.69 big, 7.82 lgraph, 9.85 small][vosk-m] | Not found | Yes, by project's description (from memory, not rechecked) | [1.8 GB, 128 MB, 40 MB][vosk-m] | [About 300 MB small, up to 16 GB big][vosk-m] | **No** without a [1.6 GB add-on][vosk-m] | ["Reconfigurable vocabulary"][vosk-r] | [Apache-2.0][vosk-r] | [Java in the repo][vosk-r]. Last tagged release [Apr 2024][vosk-tag], commits [Oct 2026][vosk-c] |
| **Zipformer EN streaming**, sherpa-onnx | Not found | [RTF 0.062, 2 threads][sh-zip], CPU not named | Yes | [296 MB][sh-assets]; 20M version [122 MB][sh-assets] | Not found | **No**: [capitals only, no marks][sh-zip]. A [separate model][sh-punct] adds them | [Yes, works with these][sh-hot] | Not checked | Official jars |

### 2.1 CPU speed, the numbers that name a machine

RTF is seconds of work per second of audio, lower is better. "x" is times faster than real time.
**None of these is someone dictating into a laptop microphone.**

| Model | Machine | Result | Who, and the catch |
|---|---|---|---|
| Parakeet int8 | i5-6500 / Ryzen 5700X / M4 Max / Jetson Nano CPU | [about 5x / 20x / 30x / 5x][trs] | Library authors. No threads or clip length given |
| Parakeet v3 int8 | i7-12700KF / i7-4790 | [about 29.7x / 17.0x][grox] | A wrapper project, on desktops. No threads given |
| Parakeet v2 int8 | RK3588 Cortex-A76, 1 to 4 threads | [RTF 0.220 down to 0.088][sh-nemo] | sherpa-onnx docs, short test clip |
| Parakeet v3 int8 | CPU not named, 2 threads | [RTF 0.325 on a 3.8 s clip, 0.028 on a long file][sh-nemo] | **Short clips cost far more per second. Dictation is short clips.** |
| Parakeet v3 int8 | 2 vCPU shared container | [RTF 0.099 over 2 minutes, 0.255 under 1 minute][vidclean] | 1,231 production jobs; the best independent data, not laptops, `onnx_asr` not sherpa-onnx |
| Parakeet | i7-8850H laptop | [3.69 s for about 10 s of audio][sacha] | Independent, 3 runs. Threads not stated. Same table: whisper.cpp base q4_0 2.48 s, faster-whisper small.en 3.74 s, base.en 1.43 s |
| faster-whisper tiny.en / base.en / small.en | i7-1370P laptop | [RTF 0.154 / 0.283 / 0.520][yazses] | Closest to a real laptop, author's own harness, clean read speech, from file |
| faster-whisper small int8 | 2 vCPU shared container | [RTF 0.160 long, about 1.25 under a minute][vidclean] | Same production set as above |
| whisper.cpp q4_0 tiny / base / small | i5-460M (2010) | [1.58x / 0.86x / 0.28x][wcpp-old] | One author; base is already slower than real time here |
| Moonshine Tiny / Small / Medium v2 | Apple M3 | [50 / 148 / 258 ms, load 8% / 18% / 29%][ms-paper] | Vendor paper |
| Moonshine Tiny / Small / Medium | "MacBook Pro" / "Linux x86" | [18 / 38 / 59 ms; 69 / 165 / 269 ms][ms-vs] | Vendor docs, CPUs not named |

**Where sources disagree:**
- Moonshine Medium on a Mac: [258 ms][ms-paper] in the paper (M3), [59 ms][ms-vs] in the docs, 107 ms in [another doc page][ms-bench] *(search summary)*. Probably different builds and Macs (inferred). I found no independent test of Moonshine on a CPU.
- faster-whisper small int8 runs from [RTF 0.13][fw] to [0.52][yazses] to [about 1.25][vidclean], depending on CPU, threads, batching and clip length.
- whisper.cpp gained [Parakeet in v1.9.0][wcpp-190], [merged 16 June 2026][wcpp-3735]; a [June write-up][devto-pk] *(search summary)* says it was not merged. The merge wins. It has [no streaming: PR #3900 was closed][wcpp-3735].
- Parakeet v3 is [6.32 and 6.18][lam] because private sets were [added to the board on Oct 9][pinggy]. Older card figures run lower than the current board; compare only within one snapshot.

**Newer, thin evidence for now:** [Granite Speech 5.0 470M TurboCTC][granite] (Apache-2.0, aimed at laptops, [GPU-batch speed only][granite-b] *(search summary)*, runs through transcribe.cpp). [Cactus Whistle][whistle] (16.9 MB, own figures, [behind Whisper base on several sets by its own page][whistle-rw] *(search summary)*). [transcribe.cpp][tcpp] (MIT, 16 model families, no Java binding) is the runtime to look at again if sherpa-onnx disappoints; [Handy][handy], a desktop dictation app, ships Parakeet in the same GGUF format. A [secondary comparison][devto-rank] *(search summary)* lists a "Nemotron 3.5 Transcribe" at the top of the board; I didn't verify it.

---

## 3. Built-in recognizers

| OS | What exists | Offline? | From the JVM | Good enough? | Privacy catch |
|---|---|---|---|---|---|
| **Windows** | `Windows.Media.SpeechRecognition` | **Free dictation: no.** [A remote web service does it][ms-win]; only short list or SRGS grammars stay local | [Needs MSIX package identity; unpackaged apps can't use it][ms-win]. Plus a native shim (inferred) | [Up to 10 s of speech][ms-win] per use. Not for dictation | Audio goes to Microsoft |
| Windows | Voice typing (Win+H) | **No.** [Uses online speech recognition][ms-priv] | Not an API; types into the focused app | Not measured here | [Voice data is sent to Microsoft][ms-priv]. Fails the owner's rule |
| Windows | Voice access | [On-device, works without internet][ms-va] *(search summary)*, after a [first language download][ms-va-q] (community answer) | No API found; an accessibility feature that types into the focused app | Not found | None stated. Whether it's in every Windows edition and language: not checked |
| Windows | Fluid dictation | [Cleanup by on-device small models, NPU machines][eleven] *(search summary)* | Not an API | Not found | Whether audio itself stays local on Win+H: **not verified** |
| Windows | `System.Speech` (old SAPI) | [Docs still exist][ms-sapi]; current support not established | .NET only | Not found. [Windows Speech Recognition was replaced by voice access from Sept 2024][ms-va] *(search summary)* | Not found |
| **macOS 26+** | `SpeechAnalyzer` with `SpeechTranscriber` or `DictationTranscriber` | **Yes.** [No network call in the path; the system downloads the model][addpipe] | Swift API, so a small Swift helper (inferred) | See below | None found. Nothing to download ourselves |
| macOS | `SFSpeechRecognizer` (old) | [Only if `requiresOnDeviceRecognition` is set][apple-req]; [server mode exists][apple-wwdc] | As above | [9.02% clean, 16.25% harder][lyon], well behind | Must force on-device, or audio can reach Apple |
| macOS | System Dictation | [Can process on the Mac; check Keyboard settings][apple-dict] | Not an API; types into the focused app | Not measured here | Sharing recordings with Apple is [a separate opt-in][apple-dict] |
| **Linux** | Nothing system-wide | n/a | n/a | n/a | [Typical GNOME or KDE has no voice typing switch][spokenly] *(vendor blog)*. [Ubuntu's Myna][myna] is dictation due in 26.10, local, Wayland/GNOME first, no API mentioned, engine not named; whether it shipped: not checked |

**macOS accuracy, sources disagree.** An app maker's test on an M2 Pro, read English, puts SpeechAnalyzer
[well ahead of Whisper Small: 2.12% vs 3.74% clean, 4.56% vs 7.95% harder][lyon]. Argmax's on an M4 Mac
mini, earnings calls, a June 2025 beta, puts it [behind: 14.0 vs 12.8, with Parakeet v2 at 11.7][argmax].
Both have a stake (each sells transcription software), and the data and OS version differ.

**macOS vocabulary:** [`DictationTranscriber` takes `contextualStrings`, `SpeechTranscriber` doesn't][apple-forum],
per a forum reply from an account named Apple_Agent. **Intel Macs:** [one developer says they aren't
supported][yap]; I found no statement from Apple.

**What this means.** OS dictation that types into any field could work in our box for free, with no code.
Whether a Compose Desktop text field accepts it on each OS is **unverified and worth a ten-minute try**
before anything is built. A later "Use macOS speech (no download)" choice is reasonable; it's the only
built-in that's offline, good and callable. It can't be the common path: nothing like it exists on
Windows or Linux.

---

## 4. Practical fit

- **sherpa-onnx:** [v2.0.1, late Oct 2026][sh-rel], with frequent releases. [JDK 8 or above; Linux, macOS and Windows on x64 and arm64][sh-java], hosted on JitPack (the docs show v1.13.5, so they lag a little). Also bundles text-to-speech; we'd ignore it.
- **Moonshine:** library [MIT; streaming models MIT; old non-English non-streaming models non-commercial][ms-lic]. [v0.1.5, Aug 2026][ms-rel]. Prebuilt libraries for [Linux, macOS arm64 and Windows x64, no macOS x86_64 or JVM asset][ms-rel], though the [README says Intel Macs are supported][ms-intel] *(search summary)*. Streaming [languages][ms-models] have no Russian. [Line limit 15 s by default][ms-opt], [under 30 s recommended][ms-vs]. Also bundles text-to-speech.
- **Top of the board:** [Cohere Transcribe, Qwen3-ASR, Canary-Qwen, Granite][pinggy]. Speed is GPU, and I found no CPU figures for a normal laptop. Several are much bigger than Parakeet by their names ([Qwen3-ASR 1.7B, Granite 2B, Canary-Qwen 2.5B][pinggy], against 0.6B). Inferred too heavy for a laptop CPU; untested.
- **Models and NVIDIA hardware:** the Parakeet and Nemotron cards [list only NVIDIA GPUs][nem]. The CPU route is the ONNX conversion by sherpa-onnx, not something NVIDIA supports.

<a id="vocab"></a>
### 4.1 Technical words

**I found no published test of any of these on developer vocabulary.** The nearest sets
are Earnings22 and SPGISpeech (finance names and terms; inferred as a loose proxy): Parakeet v3 is
[11.19 and 3.98][lam], Moonshine Medium [11.92 and 2.58][lam], Whisper large-v3 [11.29 and 2.94][lam].

- **sherpa-onnx hotwords:** [transducers only, needs `modified_beam_search`][sh-hot]. For Parakeet that has been rough: [about 20% of requests returned "Yeah." or nothing][sh-3267]. One project measured [40.9% WER on 2.0.1 vs 17.2% greedy, then 2.9% with the fix][jabber], on its own 20 clips, and [the fix landed about 10 hours after 2.0.1][jabber]. **So: wait for the next release, and test.**
- **Nemotron hotwords:** [merged Sept 14, 2026][sh-3895], and it warns that [beam search can revise earlier text between chunks][sh-3895]. The author measured [22.11% vs 23.16% greedy][sh-3895] on one clip.
- **Moonshine:** [word lists or a text passage, changeable mid-stream, output keeps your capitalisation][ms-dom]. Boost above 4.0 hurts other words, and long lists hurt too. Its benchmarks are single words in read speech.
- **Whisper:** a prompt, [about 224 tokens][wcpp-prompt], with [unpredictable results on short terms like API][whisper-terms] in one write-up *(search summary)*. It's biasing, not a guarantee.
- **camelCase and symbols:** [a study on spoken code][lit] lists split camelCase, lost underscores and mangled acronyms as failure modes that WER hides, and shows an LLM cleanup step helps. Inferred: no model here will produce `getUserInfo` by itself. The reader is a coding agent, so "get user info" may be fine in a prompt, which lowers the bar compared with typing code. Test that rather than assume.
- **A cheap trick that works with any engine:** a plain replace list after decoding ("cube control" to `kubectl`). [One sherpa-onnx user does this][sh-3267] instead of hotwords.
- **Inferred, good for later:** names from the session in hand (file names, branch) could feed hotwords per dictation, as [sherpa-onnx][sh-3895] and [Moonshine][ms-dom] both allow changing terms at run time.

---

## 5. The optional download

Voice is off, and no model is in the package. Don't go further than this.

**The setting.** Settings → Voice: off. Turn it on and it offers three choices, with their sizes, and
"Audio stays on this computer." Then a progress bar, Cancel, and later Remove.

| Choice | Model | Download | Why someone picks it |
|---|---|---|---|
| **Recommended** | Parakeet v3 int8 | [465 MB][sh-assets] | Most accurate; words appear at each pause |
| **Live words** (English) | Nemotron EN 560 ms int8 | Not found ([632 MB on disk][ow-stream]) | Words as you talk |
| **Small, for older computers** | Whisper base.en | [199 MB][sh-assets] | Light on memory |

Only offer the second if the test in section 7 says it's good. Inferred: suggest Small under about 8 GB
of RAM, given Parakeet's [1.2 to 1.7 GB peak][vidclean]. Unload after a while idle; don't hold 1.5 GB
for a mic nobody's using.

**Where from.**
- **sherpa-onnx's GitHub release, tag `asr-models`** ([assets][sh-assets]). GitHub: [no bandwidth limit, each file under 2 GiB][gh-rel]. The page shows [SHA-256 for the Parakeet files][sh-assets]. It's someone else's rolling tag, so a file could be renamed or replaced (inferred).
- **Our own GitHub release as the source of record (my suggestion).** Inferred as allowed, since CC-BY and MIT permit copies with attribution; **read the NVIDIA Open Model License first** if Nemotron is offered. Then a third party can't break the button.
- **Hugging Face** as a second source: [3,000 file requests per 5 minutes per IP when anonymous, subject to change][hf-rl], plenty for one person. [whisper.cpp's models live there][wcpp-m], with SHA-1 in the README.
- **Moonshine's own host** ([download.moonshine.ai][ms-dl]), if ever used: no checksums documented, and I found no stability statement.

**Checking it.** Keep URL, size and SHA-256 in the app, verify before unpacking, and treat the archive
as untrusted (no paths outside the folder). Download to a `.part` file with resume, [as Moonshine does][ms-dl].
No silent model updates: a new model is a new app version.

**What it adds to the app.**
- [JVM jar 188 KB; native library 7.5 to 12.5 MB per OS and CPU][sh-rel]: win-x64 7.78, osx-arm 8.99, linux-x64 9.94, osx-x64 10.2, linux-arm 12.5, win-arm 7.51.
- Silero VAD: [about 2 MB][silero] (that's the JIT build; ONNX size not found).
- **Choice:** the native library can come with the model, so people without voice pay nothing, or ship in the package at about 10 MB. Inferred, from memory and not checked: a downloaded native library on a notarized Mac may hit Gatekeeper and signing problems. If so, ship it in the package on Mac. Decide when packaging starts. The Mac's microphone permission text also has to be set in the app bundle (from memory).

---

## 6. VAD

**Needed for Parakeet and Whisper.** They decode finished pieces of audio, so something must cut speech at
pauses, which is what gives "keep listening" its phrases. It also saves the CPU in long quiet, and matters
for accuracy: [Whisper invents phrases like "thank you for watching" in silence; Parakeet returned nothing][vidclean].
Streaming models need it less. [Moonshine tunes VAD as options][ms-opt] and it's on by default, and
[sherpa-onnx had to fix Moonshine v2 hallucinating on silence][sh-rel] in 1.13.8.

**Which: Silero.** [MIT, about 2 MB, under 1 ms per 30 ms chunk on one thread][silero] (vendor figures; the
repo's badge alt text says CC BY-NC, its text and [sherpa-onnx's docs][sh-vad] say MIT). Sherpa-onnx also
supports [TEN VAD under a modified Apache license][sh-vad]; no reason to take that on. The VAD pages list
[Android, WebAssembly and C examples][sh-vad]; a Java VAD API is **not confirmed**, so check it first.

The phone's rule carries over: a long quiet doesn't end dictation, only you do (`DECISIONS.md`). So the
VAD splits text; it never stops listening.

---

## 7. What has to be measured

Published numbers won't settle this. What decides it:
1. **Does Parakeet's phrase-at-a-pause feel fast enough, or do we need live words?** This picks the default.
2. **How many technical words survive?** No source has it.
3. **Time from the end of a short phrase to text**, on a weak machine. [Short clips are the weak spot][sh-nemo].
4. **Memory and cold start** on an 8 GB laptop.
5. **Are the punctuation and capitals decent**, on Nemotron especially ([the sources differ](#2-comparison)), when you speak in fragments?

**A small test (an afternoon or two, once a harness exists):**
- **Machines:** the owner's Linux box; an old 4-core Windows laptop (the one that matters); an M-series Mac; an Intel Mac if one's at hand.
- **Audio:** the same person, on the laptop's own mic, in a normal room, reading 40 real prompts from past sessions, roughly 100 technical terms in all (`git push`, `kubectl`, TypeScript, Mermaid, `getUserInfo`, real file names). Add a second speaker with a different accent if possible. Keep the recordings local.
- **Candidates:** Parakeet v3 int8, Nemotron EN 560 ms, Whisper base.en, and Moonshine Medium if the shim is cheap. Greedy first, then hotwords on for each one that allows it, with the release containing the fix.
- **Run:** feed the WAVs at real speed through the actual sherpa-onnx JVM binding (not its Python, and not faster than real time) from one small Kotlin harness. Threads: 2 and 4.
- **Record:** terms correct, exact and ignoring case; WER with and without punctuation; time to first word; time from last word to final text; peak memory; CPU while talking; cold start.
- **Suggested bars (mine, adjust):** final text under 1 s after a pause on the old laptop, p50; first words under 1 s for streaming; at least 85 of 100 terms recognisably right; keeps real time with an IDE open.
- **Then:** the owner dictates for ten minutes live with the top two, because replay doesn't show how it feels. Also try the OS dictation in a Compose field on Windows and Mac (section 3).
- **Against:** Soniox on the same recordings, if he's fine with that once for a baseline. It's the same audio going to a service, so ask first.

---

## 8. Text-to-speech

Out of scope. sherpa-onnx and Moonshine both include it, so a later read-aloud wouldn't need a second runtime.

---

## 9. Not established

Moonshine's punctuation. RAM for Nemotron and Moonshine. Any CPU RTF for a current mid-range laptop with
threads stated for Parakeet through sherpa-onnx. Whether Nemotron's hotwords are in a tagged release. Size
of the Nemotron packages. Whether a Java VAD API exists in sherpa-onnx. Windows Voice access's availability.
Whether Compose text fields take OS dictation. Mac signing rules for downloaded native code.

---

## Sources

**Boards and benchmarks.** [Open ASR Leaderboard Space][oasr-space] · [its repo][oasr-repo] · [its paper][oasr-paper] · [Pinggy, Oct 9 snapshot][pinggy] · [Local AI Master, Aug 18 copy][lam] · [BenchmarkList, May 6 copy][bl] · [dev.to ranking][devto-rank] · [YazSes paper (i7-1370P)][yazses] · [VidClean, 1,231 jobs][vidclean] · [Sacha Chua, i7-8850H][sacha] · [i5-460M report][wcpp-old] · [transcribe-rs][trs] · [Parakeet FastAPI project][grox] · [Lost in Transcription][lit] · [Whisper on technical terms][whisper-terms]

**sherpa-onnx.** [Repo][sh-repo] · [Releases][sh-rel] · [Model assets][sh-assets] · [Java setup][sh-java] · [NeMo transducers][sh-nemo] · [Nemotron streaming][sh-nemotron] · [Zipformer][sh-zip] · [Hotwords][sh-hot] · [Punctuation][sh-punct] · [VAD][sh-vad] · [PR #3895][sh-3895] · [Issue #3267][sh-3267] · [Issue #4131][sh-4131] · [jabberwrite #7][jabber]

**NVIDIA.** [Parakeet v3][pk3] · [Parakeet Unified][pku] · [Nemotron][nem] · [Canary 180M][canary180]

**Moonshine.** [Paper][ms-paper] · [Moonshine vs Whisper][ms-vs] · [Available models][ms-models] · [Domain customisation][ms-dom] · [Options][ms-opt] · [Downloading][ms-dl] · [Quickstart][ms-qs] · [License][ms-lic] · [Releases][ms-rel] · [Benchmarks page][ms-bench] · [Fork README][ms-intel] · [Medium GGUF][ms-gguf-m] · [Small GGUF][ms-gguf-s]

**Whisper.** [whisper.cpp][wcpp] · [Models][wcpp-m] · [Java binding][wcpp-java] · [v1.9.0][wcpp-190] · [PR #3735][wcpp-3735] · [Prompt limit][wcpp-prompt] · [faster-whisper][fw] · [its releases][fw-rel] · [parakeet.cpp on dev.to][devto-pk] · [LocalAI on parakeet.cpp][localai]

**Others.** [Vosk models][vosk-m] · [Vosk repo][vosk-r] · [Vosk releases][vosk-tag] · [Vosk commits][vosk-c] · [Silero VAD][silero] · [Granite 5.0 card][granite] · [Granite write-up][granite-b] · [Whistle][whistle] · [Whistle article][whistle-rw] · [transcribe.cpp][tcpp] · [Handy][handy] · [OpenWhispr comparison][ow-cmp] · [OpenWhispr streaming][ow-stream]

**Hosting.** [GitHub release limits][gh-rel] · [Hugging Face rate limits][hf-rl]

**Operating systems.** [Windows speech API][ms-win] · [Windows speech privacy][ms-priv] · [Voice access][ms-va] · [Voice access offline Q&A][ms-va-q] · [DictationGrammar][ms-sapi] · [Fluid dictation thread][eleven] · [macOS Dictation][apple-dict] · [requiresOnDeviceRecognition][apple-req] · [WWDC19 speech][apple-wwdc] · [Apple forum on technical terms][apple-forum] · [SpeechAnalyzer overview][addpipe] · [Lyonesse benchmark][lyon] · [Argmax benchmark][argmax] · [Yap wiki][yap] · [Myna][myna] · [Spokenly on Linux][spokenly]

[oasr-space]: https://huggingface.co/spaces/hf-audio/open_asr_leaderboard
[oasr-repo]: https://github.com/huggingface/open_asr_leaderboard
[oasr-paper]: https://arxiv.org/html/2510.06961v4
[pinggy]: https://pinggy.io/blog/best_open_source_self_hosted_speech_to_text_models/
[lam]: https://localaimaster.com/blog/best-local-speech-to-text-models
[bl]: https://benchmarklist.com/benchmarks/open_asr_leaderboard/
[devto-rank]: https://dev.to/lucas_ferreira/melhores-modelos-open-source-de-reconhecimento-de-fala-em-2026-28i7
[yazses]: https://arxiv.org/pdf/2607.28878
[vidclean]: https://vidclean.net/blog/parakeet-vs-whisper-cpu-transcription/
[sacha]: https://sachachua.com/blog/2026/01/emacs-and-whisper-el-trying-out-different-speech-to-text-backends-and-models/
[wcpp-old]: https://github.com/ggml-org/whisper.cpp/discussions/3752
[trs]: https://lib.rs/crates/transcribe-rs
[grox]: https://github.com/groxaxo/parakeet-tdt-0.6b-v3-fastapi-openai
[lit]: https://arxiv.org/pdf/2601.15339
[whisper-terms]: https://www.f22labs.com/blogs/how-to-evaluate-whisper-small-before-fine-tuning/
[sh-repo]: https://github.com/k2-fsa/sherpa-onnx
[sh-rel]: https://github.com/k2-fsa/sherpa-onnx/releases
[sh-assets]: https://github.com/k2-fsa/sherpa-onnx/releases/expanded_assets/asr-models
[sh-java]: https://k2-fsa.github.io/sherpa/onnx/java-api/non-android-java.html
[sh-nemo]: https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-transducer/nemo-transducer-models.html
[sh-nemotron]: https://k2-fsa.github.io/sherpa/onnx/nemo/nemotron-streaming.html
[sh-zip]: https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html
[sh-hot]: https://k2-fsa.github.io/sherpa/onnx/hotwords/index.html
[sh-punct]: https://k2-fsa.github.io/sherpa/onnx/punctuation/index.html
[sh-vad]: https://k2-fsa.github.io/sherpa/onnx/vad/index.html
[sh-3895]: https://github.com/k2-fsa/sherpa-onnx/pull/3895
[sh-3267]: https://github.com/k2-fsa/sherpa-onnx/issues/3267
[sh-4131]: https://github.com/k2-fsa/sherpa-onnx/issues/4131
[jabber]: https://github.com/marklechtermann/jabberwrite/issues/7
[pk3]: https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3
[pku]: https://huggingface.co/nvidia/parakeet-unified-en-0.6b
[nem]: https://huggingface.co/nvidia/nemotron-speech-streaming-en-0.6b
[canary180]: https://huggingface.co/nvidia/canary-180m-flash
[ms-paper]: https://arxiv.org/html/2602.12241
[ms-vs]: https://moonshine-voice.readthedocs.io/en/latest/moonshine-vs-whisper/
[ms-models]: https://moonshine-voice.readthedocs.io/en/latest/models/available-models/
[ms-dom]: https://moonshine-voice.readthedocs.io/en/latest/models/domain-customization/
[ms-opt]: https://moonshine-voice.readthedocs.io/en/latest/api/options/
[ms-dl]: https://moonshine-voice.readthedocs.io/en/latest/using/downloading-models/
[ms-qs]: https://moonshine-voice.readthedocs.io/en/latest/quickstart/
[ms-lic]: https://raw.githubusercontent.com/moonshine-ai/moonshine/main/LICENSE
[ms-rel]: https://api.github.com/repos/moonshine-ai/moonshine/releases?per_page=1
[ms-bench]: https://www.mintlify.com/moonshine-ai/moonshine/models/benchmarks
[ms-intel]: https://github.com/thinhlpg/moonshine
[ms-gguf-m]: https://huggingface.co/handy-computer/moonshine-streaming-medium-gguf
[ms-gguf-s]: https://huggingface.co/handy-computer/moonshine-streaming-small-gguf
[wcpp]: https://raw.githubusercontent.com/ggml-org/whisper.cpp/master/README.md
[wcpp-m]: https://raw.githubusercontent.com/ggml-org/whisper.cpp/master/models/README.md
[wcpp-java]: https://raw.githubusercontent.com/ggml-org/whisper.cpp/master/bindings/java/README.md
[wcpp-190]: https://github.com/ggml-org/whisper.cpp/releases/tag/v1.9.0
[wcpp-3735]: https://github.com/ggml-org/whisper.cpp/pull/3735
[wcpp-prompt]: https://huggingface.co/spaces/Xenobd/whisper.cpp/commit/15949a981ba2a5797602634844ffb70bad7be11c
[fw]: https://github.com/SYSTRAN/faster-whisper
[fw-rel]: https://api.github.com/repos/SYSTRAN/faster-whisper/releases?per_page=2
[devto-pk]: https://dev.to/creeta/nemo-out-gguf-in-how-parakeetcpp-ports-nvidia-asr-to-c-3g5n
[localai]: https://localai.io/blog/parakeet-cpp-asr-on-cpu/
[vosk-m]: https://alphacephei.com/vosk/models
[vosk-r]: https://github.com/alphacep/vosk-api
[vosk-tag]: https://api.github.com/repos/alphacep/vosk-api/releases?per_page=3
[vosk-c]: https://api.github.com/repos/alphacep/vosk-api/commits?per_page=3
[silero]: https://github.com/snakers4/silero-vad
[granite]: https://huggingface.co/ibm-granite/granite-speech-5.0-470m-turboctc
[granite-b]: https://www.orcarouter.ai/blog/granite-speech-5-0-470m-turboctc-apache-explained
[whistle]: https://huggingface.co/Cactus-Compute/whistle
[whistle-rw]: https://runtimewire.com/article/cactus-whistle-16-9mb-local-speech-model
[tcpp]: https://github.com/handy-computer/transcribe.cpp
[handy]: https://github.com/cjpais/Handy
[ow-cmp]: https://openwhispr.com/blog/parakeet-vs-whisper-vs-nemotron
[ow-stream]: https://openwhispr.com/blog/local-streaming-speech-to-text
[gh-rel]: https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases
[hf-rl]: https://huggingface.co/docs/hub/en/rate-limits
[ms-win]: https://learn.microsoft.com/en-us/windows/apps/develop/input/speech-recognition
[ms-priv]: https://support.microsoft.com/en-us/windows/privacy/speech-voice-activation-inking-typing-and-privacy
[ms-va]: https://support.microsoft.com/en-us/accessibility/windows/voice-access/use-voice-access-to-control-your-pc-author-text-with-your-voice
[ms-va-q]: https://learn.microsoft.com/en-us/answers/questions/4123211/voice-access-for-offline-use
[ms-sapi]: https://learn.microsoft.com/de-de/dotnet/api/system.speech.recognition.dictationgrammar
[eleven]: https://www.elevenforum.com/t/enable-or-disable-fluid-dictation-in-voice-typing-on-windows-11.42286/latest
[apple-dict]: https://support.apple.com/guide/mac-help/use-dictation-mh40584/mac
[apple-req]: https://developer.apple.com/documentation/speech/sfspeechrecognitionrequest/requiresondevicerecognition
[apple-wwdc]: https://developer.apple.com/videos/play/wwdc2019/256/
[apple-forum]: https://developer.apple.com/forums/thread/801877
[addpipe]: https://blog.addpipe.com/apple-speechanalyzer-api/
[lyon]: https://lyonesse.app/blog/apple-speech-api-benchmark.html
[argmax]: https://www.argmaxinc.com/blog/apple-and-argmax
[yap]: https://github.com/FrigadeHQ/yap/wiki
[myna]: https://linuxiac.com/ubuntu-26-10-steps-into-ai-with-local-speech-to-text/
[spokenly]: https://spokenly.app/blog/speech-to-text-linux
