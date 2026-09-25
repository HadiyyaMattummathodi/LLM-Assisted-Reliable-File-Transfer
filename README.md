# Reliable file transfer over UDP with a local LLM

COSC 630/788 Advanced Computer Networks, Assignment 1.

Java performs file transfer, validates commands and computes every measurement. A local Qwen model, through Ollama, translates natural-language requests and comments on the measured results. The model has no file, shell or socket tools. The networking engine has no third-party dependencies.

This is project version 7, checked against the uploaded Windows results. The Java source is unchanged from the tested version; all 14 source SHA-256 values match the experiment manifest. See [requirements and evidence](docs/REQUIREMENTS.md), [validation results](VALIDATION.txt) and the [Windows evidence review](docs/WINDOWS-ACCEPTANCE.md). The final report, protocol document and recorded demonstration remain separate deliverables.

The included `evidence/windows-v7/` folder contains the actual Windows experiment and chat logs. The original runs passed: 124 automated Java checks, 50 measured experimental trials plus an excluded warm-up, and real local-model send/status/explanation/rejection demonstrations. A teammate can follow the commands below to reproduce the procedure. The project owner does not need to repeat completed runs merely because this documentation and evidence package has been updated.

## Windows: build and verify

Prerequisite: a JDK with `java` and `javac` on PATH. Java 8 is supported. Open a normal PowerShell window in this folder, where `build.cmd` is located. You do not need administrator access or Colab.

```powershell
.\verify.cmd
```

This builds the source, then runs the command/analysis validator tests, reliable-transfer tests and adversarial protocol tests. Some tests deliberately trigger failures; the final PASS lines and a zero exit code indicate success. Do not run another receiver or transfer concurrently: receiver A uses localhost port 9000.

For a simple transfer:

```powershell
.\demo.cmd baseline hello.txt
```

The engine checks SHA-256, and the combined demo also compares source and received files byte for byte. Files appear in `received/`; raw events and summaries appear in a new directory under `runs/`.

All commands below are for Windows PowerShell, with this project folder as the working directory.

## Run the required experiments

```powershell
.\experiments.cmd
```

The default is five repetitions of ten configurations: **50 measured transfers**, plus one excluded warm-up. Seeds vary between repetitions; configuration order is shuffled reproducibly within each repetition. Every transfer uses actual UDP sockets and independently checks the reconstructed bytes.

| Configuration | Files | What changes |
| --- | --- | --- |
| Baseline | 32 KiB and 1 MiB | No intentional loss, corruption or delay |
| Lossy | 32 KiB and 1 MiB | 3% independent outgoing loss at each endpoint |
| Delayed | 32 KiB and 1 MiB | Each outgoing packet waits 25–50 ms, allowing reordering |
| Short timeout | 32 KiB and 1 MiB | Same delay; timeout reduced from 250 to 30 ms |
| Narrow window | 32 KiB | Same delay; window reduced from 32768 to 1024 bytes |
| Wide window | 1 MiB | Same delay; window increased from 32768 to 65536 bytes |

The comparisons change one setting at a time against the corresponding delayed case. A fixed random seed reproduces the pseudo-random sequence, not exact timing or the same loss trace under every window/timeout: scheduling and different retry traffic affect subsequent draws.

For a quick installation check only, use `.\experiments.cmd 1`. Use the default five repetitions for evaluation. Runtime depends on your machine; wait for `ALL 50 EXPERIMENT TRIALS COMPLETE AND AUDITED`. A failed trial remains recorded, and the command exits unsuccessfully rather than silently dropping it.

Each batch has:

- `plan.csv`: all planned configurations, seeds, order and trial folders.
- `manifest.json`: progress, failures, timestamps, environment, input hashes and source hashes.
- `all-results.csv`: one row per measured trial, saved after every trial.
- `aggregate.csv`: per-configuration mean, sample standard deviation, minimum, maximum and sample count. Warm-up is excluded; unavailable RTT values are not converted to zero.
- `trial-.../`: sender/receiver event logs, endpoint summaries and combined `summary.csv`.
- `audit-report.txt`: results of independently recalculating counters and metrics from the raw events.

You can repeat the audit using the batch path printed by the program:

```powershell
.\audit.cmd "runs\experiments-v7-YYYYMMDD-HHMMSS-SSS-ID"
```

The audit checks every eligible RTT sample, unique delivered bytes, sequence-window limits, drop/duplicate types, goodput and overhead. While source and output files still exist, it also repeats the byte comparison. An archived logs-only audit cannot independently recheck missing file contents.

See [experiment methods and metric definitions](docs/EXPERIMENTS.md) before interpreting results. Run on an otherwise quiet laptop, retain all trials and record any changes to setup.

## Use the local LLM

Start the installed Ollama application. This project expects the local endpoint at `127.0.0.1:11434` and model `qwen2.5:1.5b`. If the model has already been downloaded, there is no need to download it again.

```powershell
ollama run qwen2.5:1.5b "Reply with OK"
.\chat.cmd
```

At the **`You>` prompt**, enter these one at a time:

```text
Send large.bin to receiver A using the lossy scenario and a 65536 byte window
:wait
How much has been delivered and what is the current throughput?
Summarize packet loss and retries for this transfer
Why were there duplicate packets at the receiver?
:unsafe
:quit
```

For a live status demonstration, start another chat transfer with `Send large.bin to receiver A using the delayed scenario, a 1024 byte window and a 250 ms timeout`, then ask the status question before `:wait`. This deliberately slow configuration makes progress observable. Live goodput means average useful throughput **so far**, not an instantaneous bandwidth estimate.

The chat shows the model's structured proposal, Java's decision, measured values and the model's observations. It saves all requests, raw replies, rejections and accepted explanations to `conversation.jsonl`. The explanation uses a frozen snapshot; an invalid or truncated explanation gets one retry. No model error reruns the file transfer.

`:unsafe` injects a known path-traversal proposal directly into the validator; it is explicitly a Java validator demonstration, not a model call. For an additional model-facing example, ask to send `../private.txt` and inspect the actual proposal and rejection.

Java verifies cited fields and decimal numbers, including supported percentage conversions. **That does not prove the prose, units or causal explanation is correct.** Review the explanation against the displayed evidence. Loss and duplicate questions now focus on DATA drops, ACK drops and repeated DATA, so receiver duplicates are not confused with repeat ACKs at the sender. Do not call a superficial or rejected response a successful explanation.

The supplied real session used Ollama 0.34.2 and `qwen2.5:1.5b` (listed model ID `65ec06548149`). Both explanation requests initially repeated a field and were rejected; each retry passed. The lost-ACK explanation is supported by the recorded packet events. The phrase "packets were lost and not delivered" in the loss response is ambiguous: original attempts were dropped, but retries recovered the complete file. Retain the raw wording and discuss this limitation honestly. The validator checks numbers and references, not the semantic correctness of unrestricted prose.

## How the implementation works

| File | Responsibility |
| --- | --- |
| `Packet.java` | Binary framing, transfer ID, sequence, payload length and CRC32 |
| `Transfer.java` | START/READY, selective-repeat DATA/ACK, SHA-256 and FIN/DONE |
| `Link.java` | UDP sockets and outgoing loss/delay/corruption simulation |
| `Config.java` | Allowed file paths, endpoint and parameter bounds |
| `Metrics.java` | Raw event log, counters, RTT samples and summaries |
| `Command.java`, `Json.java` | Strict model command format and deterministic validation |
| `OllamaClient.java`, `ChatConsole.java` | Fixed local model endpoint, chat lifecycle and grounded observations |
| `Experiments.java`, `EvidenceAudit.java` | Repeated experiments and raw-log verification |
| `InterfaceTests.java`, `ProtocolTests.java` | Interface, malformed traffic and failure tests |

In plain terms: the sender numbers the chunks and keeps a limited range in flight. An ACK confirms a chunk. If its timer expires, the sender resends it. The receiver remembers received chunk numbers, accepts out-of-order chunks and acknowledges repeats without writing their bytes twice. A whole-file hash and final completion exchange are required before the sender reports success.

For a code-reading route, start with `Packet`, then `Transfer.Sender`, `Transfer.Receiver`, `Metrics`, and finally `Command` and `ChatConsole`. The working protocol notes and sequence source remain under `docs/`; reconcile the final protocol document with the tested release later.

## Independent processes

The combined demo is convenient, but the sender and receiver also run separately. In two PowerShell windows in this folder:

```powershell
# Window 1
.\receiver.cmd lossy
# Window 2
.\send.cmd small.bin lossy
```

The standalone receiver handles one transfer, retains DONE responses through the retry horizon, then exits. Use the same scenario on both sides. Its logs and the sender's logs are in separate run folders; the combined experiment harness provides both-direction overhead automatically.

## Share with a teammate on GitHub

Put the project contents in the repository root: `README.md`, `src/`, `tests/`, `docs/`, command scripts, `.gitignore`, `.gitattributes` and `evidence/`. Retain `inputs/hello.txt`; binary test inputs regenerate automatically. If using an existing project folder, run the updated `update-existing.cmd` **from the newly extracted folder** with the existing folder as its argument.

`.gitignore` excludes compiled classes, received files, run logs, archives and local environment files. Before committing, inspect `git status` and the actual files being included. Do not upload the Ollama model, your full drive, unrelated assignments or personal conversation logs. The included `evidence/windows-v7/` directory is deliberately retained by Git and contains the uploaded experiment, self-test and real-model records. New runs under the root `runs/` folder remain ignored. Keep the complete evidence directory so your teammate can trace the reported results.

For review, export the run evidence after the experiments and real-model checks:

```powershell
Compress-Archive -Path .\runs -DestinationPath .\assignment-runs-v7.zip -Force
```

This handoff contains both source and the reviewed Windows evidence. The original uploaded evidence ZIP should also be kept as a backup. Both teammates should be able to explain selective repeat, CRC versus SHA-256, lost ACKs and duplicates, Karn's RTT rule, the metric denominators and the Java/LLM validation boundary.

## Scope and attribution

This is an educational, single-transfer, localhost system with files up to 16 MiB. It buffers a file in memory and has no congestion control, encryption, authentication or resume support. The loss/delay shim is above the OS socket; recorded byte totals count the custom protocol datagram contents, excluding UDP/IP/link headers. Loopback results are not Internet performance claims.

Java source and project documentation were developed with ChatGPT/Codex assistance. Local Qwen provides the runtime natural-language interface. See `AI-ASSISTANCE.txt`; students must review, understand and disclose assistance according to course policy. No existing reliable-transfer implementation is used.
