# LLM-Assisted Reliable File Transfer

A Java application that transfers files reliably over UDP and provides an optional natural-language interface through a local language model. Built for COSC 630/788 Advanced Computer Networks.

UDP does not guarantee delivery or ordering. This project adds numbered file chunks, acknowledgements, selective retransmission, duplicate handling, packet checksums and whole-file verification. Java controls every transfer and calculates the measurements. The language model translates requests into validated commands and explains recorded results.

The instructions below use Windows PowerShell. Both transfer endpoints run on the same computer, using real UDP sockets with controlled loss, delay and corruption.

## Requirements

- A Java Development Kit (JDK), with both `java` and `javac` available in PowerShell. The project targets Java 8 and was tested with OpenJDK 1.8.0_422 on Windows 11. [Eclipse Temurin JDK 8](https://adoptium.net/temurin/releases/?version=8) is one installation option; select Windows and the JDK package.
- [Ollama for Windows](https://ollama.com/download/windows), only for the chat interface. The transfer demos, automated checks and experiments work without it.

The Java application uses the standard library; there are no Maven, Gradle or Python dependencies for the commands in this guide. The chat interface uses a local model and requires no paid API or API key.

## Quick start

### 1. Open the project

Clone the repository, or use GitHub's **Code → Download ZIP** and extract it. Open PowerShell in the folder containing `README.md`, `build.cmd` and `src/`. For a ZIP download, navigate into the extracted folder before running commands.

All PowerShell examples below assume this folder is the current directory. Confirm the compiler is available:

```powershell
java -version
javac -version
```

If either command is unavailable, install the JDK, ensure its `bin` directory is on PATH, and reopen PowerShell.

### 2. Build and send a file

```powershell
.\build.cmd
.\demo.cmd baseline hello.txt
```

The demo creates any missing sample inputs, starts a receiver, sends the file and checks the result. A successful run prints `SUCCESS`, confirms SHA-256 verification and displays the path to `summary.csv`. The demo also compares the original and received files byte for byte.

The received copy is saved under `received/` with a unique transfer ID in its filename. Measurements are saved in a new folder under `runs/`.

Try simulated packet loss or delay:

```powershell
.\demo.cmd lossy large.bin
.\demo.cmd delayed small.bin
```

Run commands sequentially. Receiver A uses `127.0.0.1:9000`, so only one demo, receiver, experiment batch or chat transfer can use it at a time. The demo, chat and experiment commands start their own receivers.

### 3. Run the automated checks

```powershell
.\verify.cmd
```

This rebuilds the project and runs 124 checks covering command validation, model-response validation, reliable delivery, malformed packets, corruption, lost messages, retry limits and chat lifecycle handling. It does not require Ollama.

Some checks deliberately cause errors, including a hash mismatch and a busy port. Successful verification ends with all three `PASS` summaries and `VALIDATION COMPLETE`. If verification stops early, investigate the failure before running experiments.

## Chat interface

### Set up the local model

Install and open Ollama. Its Windows application runs the local service in the background. Reopen PowerShell after installation, then download and test the model:

```powershell
ollama pull qwen2.5:1.5b
ollama run qwen2.5:1.5b "Reply with OK"
```

The download is only needed once. Keep Ollama running and start the project chat from the project folder:

```powershell
.\chat.cmd
```

The application connects to Ollama at `http://127.0.0.1:11434`. See the [Ollama Windows documentation](https://docs.ollama.com/windows) for installation details.

### Send a file and inspect the result

Enter these lines one at a time at the application's `You>` prompt, not at the PowerShell prompt:

```text
Send large.bin to receiver A using the lossy scenario and a 65536 byte window
:wait
How much has been delivered and what is the current throughput?
Summarize packet loss and retries for this transfer
Why were there duplicate packets at the receiver?
```

The model proposes a structured command. Java validates the filename, receiver, scenario and numeric settings before starting a transfer in the background. `:wait` waits for that transfer to finish; explanation requests require a completed transfer.

To observe progress while a transfer is running, enter:

```text
Send large.bin to receiver A using the delayed scenario, a 1024 byte window and a 250 ms timeout
```

Then enter `:status` while it is running. This deliberately slower configuration makes progress easier to observe. Live goodput is average useful throughput so far, rather than an instantaneous rate.

| Chat command | Purpose |
| --- | --- |
| `:status` | Display Java's current measurements directly, without a model call |
| `:wait` | Wait for the active transfer to finish |
| `:unsafe` | Demonstrate rejection of a path-traversal command; this is a direct validator test, without a model call |
| `:help` | Show usage examples |
| `:quit` | Exit the chat |

Chat supports the scenarios `baseline`, `lossy`, `delayed` and `corrupt`. Default settings are a 32768-byte window and a 250 ms timeout. With the default 1024-byte chunks, the permitted chat window is 1024–65536 bytes; the permitted timeout is 20–5000 ms. Use exact byte counts in requests to avoid ambiguous units.

Each session saves requests, model replies, validation decisions and measurement snapshots to `runs/<chat-session>/conversation.jsonl`. Transfer results are saved in their own folders under `runs/`.

Model explanations use a fixed measurement snapshot. Java checks field references and numeric values, with one retry for an invalid or incomplete explanation. These checks do not establish that the model's interpretation is correct: compare its wording with the measurements and packet logs. A rejected explanation leaves the completed transfer and its results unchanged.

## Reproduce the experiments

Close other receivers and finish any active chat transfer. From the project folder, run:

```powershell
.\experiments.cmd
```

The default batch performs one excluded warm-up followed by 50 measured transfers: five repetitions of ten configurations. Each successful transfer includes SHA-256 verification and an independent byte-for-byte comparison.

The sample files are `small.bin` (32 KiB) and `large.bin` (1 MiB). Unless changed below, the chunk size is 1024 bytes, the window is 32768 bytes and the timeout is 250 ms.

| Configuration | Files | Settings |
| --- | --- | --- |
| Baseline | Small and large | No intentional loss, corruption or delay |
| Lossy | Small and large | 3% independent outgoing packet-loss probability at each endpoint |
| Delayed | Small and large | 25 ms base delay plus 0–25 ms random delay on each outgoing packet |
| Short timeout | Small and large | Same delay; timeout reduced to 30 ms |
| Narrow window | Small | Same delay; window reduced to 1024 bytes |
| Wide window | Large | Same delay; window increased to 65536 bytes |

Compare each timeout or window variant with the delayed case for the same file size. The impairment layer schedules outgoing packets, so variable delays can cause reordering. It does not impose a bandwidth limit.

The five repetitions use sender seeds 42–46, with a separate receiver random stream and reproducibly shuffled configuration order. Seeds reproduce the random-number procedure; exact timings and packet loss locations can vary with scheduling and retransmissions. Keep all trials when interpreting results.

Wait for `ALL 50 EXPERIMENT TRIALS COMPLETE AND AUDITED`. Failed trials remain in the results, and a failed or incomplete batch does not pass the final audit.

For a shorter installation check, run `.\experiments.cmd 1` in PowerShell. This performs ten measured transfers and one warm-up; use the default five repetitions for the full evaluation.

### Results and auditing

The program prints the new experiment folder under `runs/`. It contains:

| File or folder | Contents |
| --- | --- |
| `plan.csv` | Configurations, seeds, run order and trial folders |
| `manifest.json` | Environment, source/input hashes, timestamps and completion status |
| `all-results.csv` | One row per measured trial, including failures |
| `aggregate.csv` | Per-configuration means, sample standard deviations, minima, maxima and counts |
| `trial-*/` | Sender and receiver event logs, endpoint summaries and combined `summary.csv` |
| `warmup-baseline-large/` | Warm-up records, excluded from the aggregates |
| `audit-report.txt` | Checks recalculated from the raw event logs |

After a batch, these commands audit the most recently created experiment folder again:

```powershell
$batch = Get-ChildItem .\runs -Directory -Filter "experiments-*" | Sort-Object CreationTime -Descending | Select-Object -First 1
.\audit.cmd $batch.FullName
```

The audit checks packet counters, unique delivered bytes, sequence/window bounds, RTT samples, goodput and overhead. It also compares source and received files when those files are still available. Retain the input and received files to allow those comparisons to be repeated.

Recorded Windows experiments and real-model chat logs are included in the [evidence folder](evidence/windows-v7/README.md). Its README explains how to audit an archived copy without changing the original records. Archived logs do not include the received binaries, so they cannot independently repeat the original byte comparisons.

See [measurement and experiment methods](docs/EXPERIMENTS.md) for the full definitions and reproducibility details.

## Understanding the measurements

| Measurement | Meaning |
| --- | --- |
| `payload_bytes` | Unique useful file bytes acknowledged at the sender; duplicates and retries do not increase this total |
| `goodput_mbps` | Useful bytes × 8 ÷ elapsed seconds ÷ 1,000,000 |
| `retransmissions` | Requests sent again after an acknowledgement was not received in time |
| `retransmission_ratio` | Retransmitted requests divided by all sender attempts, including control traffic |
| `rtt_mean_ms`, `rtt_p95_ms` | Mean and 95th-percentile round-trip time for eligible DATA acknowledgements |
| `emitted_overhead_share` | In combined results, the fraction of both endpoints' emitted custom datagram bytes beyond one copy of the file |

Sender elapsed time includes setup, metadata exchange, data transfer and final confirmation. RTT statistics exclude retransmitted DATA because its acknowledgement cannot be tied unambiguously to a particular attempt. A missing RTT value is unavailable, not zero.

A timeout can result from either loss or a late acknowledgement. A lost ACK can cause repeated DATA even though the receiver already has the original chunk. The receiver acknowledges the repeat without writing the bytes twice. Similarly, a 3% configured loss probability does not imply that exactly 3% of packets will be dropped in every run.

## Transfer your own file

Place the file inside `inputs/`, then use its filename:

```powershell
.\demo.cmd baseline example.txt
```

Filenames must start with a letter or digit and contain only letters, digits, periods, underscores or hyphens, up to 100 characters. Spaces and directory paths are not accepted. The maximum file size is 16 MiB.

Keep the standard sample files unchanged. The experiment harness checks the binary fixtures against their expected contents before running.

## Run sender and receiver separately

The demo starts both endpoints automatically. To run them as separate processes, first build the project and generate the sample files:

```powershell
.\build.cmd
java -cp out acn.Main files
```

In the first PowerShell window, from the project folder:

```powershell
.\receiver.cmd lossy
```

Once the receiver is listening, use a second PowerShell window in the same folder:

```powershell
.\send.cmd small.bin lossy
```

Use the same scenario in both commands. The standalone receiver handles one transfer, remains available briefly to answer repeated completion messages, and then exits. Each endpoint prints its own log folder. Use the combined demo or experiment harness for a summary that includes both directions' overhead.

## Project structure

| Location | Purpose |
| --- | --- |
| `src/acn/` | Java source, including the transfer engine, chat interface, tests and experiment runner |
| `*.cmd` | Windows build and launch scripts |
| `inputs/` | Files available to send; missing sample files are generated automatically by the demo, checks, experiments or chat |
| `out/` | Compiled classes, generated by the build |
| `received/` | Reconstructed files, generated by transfers |
| `runs/` | New measurements and chat logs |
| `evidence/` | Recorded Windows experiment and chat evidence included with the repository |
| `docs/` | Protocol notes, code walkthrough, metric definitions and validation documentation |
| `tests/` | Additional HTTP adapter test tooling |

Start reading the implementation with `Packet.java` for framing, `Transfer.java` for sender/receiver behaviour, and `Metrics.java` for measurements. `Link.java` implements the UDP socket and impairment layer. `Command.java` validates model proposals, while `ChatConsole.java` and `OllamaClient.java` implement the chat interface. `Experiments.java` runs the batch, and `EvidenceAudit.java` checks the saved evidence.

Generated build output, received files and new root-level run logs are excluded by `.gitignore`. The recorded `evidence/` folder is tracked. Rebuild after changing Java source.

## Troubleshooting

| Problem | What to check |
| --- | --- |
| `build.cmd` is not recognized | Open PowerShell in the extracted project folder containing `build.cmd`, and enter `.\build.cmd`. |
| `javac` is not recognized | Install a JDK, ensure its `bin` directory is on PATH, then reopen PowerShell. A runtime alone is insufficient. |
| Java cannot find `acn.Main` | Run `.\build.cmd` and resolve any compilation errors before launching another script. |
| `Address already in use` | Stop the receiver or transfer already using port 9000. Run the demo, chat transfers and experiments sequentially. |
| `ollama` is not recognized | Install Ollama for Windows and reopen PowerShell. |
| `Cannot reach Ollama` or model unavailable | Open Ollama, run `ollama pull qwen2.5:1.5b`, and confirm the standalone `ollama run` command works. |
| `Scenario is not allowlisted` | Explicitly name `baseline`, `lossy`, `delayed` or `corrupt` in the chat request and inspect the model's proposal. |
| Explanation rejected | Check the displayed Java measurements and the conversation log. The completed transfer remains valid; the explanation failed validation. |
| Standard input differs from the test fixture | Rename the modified sample as a backup, then rerun the command to regenerate the expected fixture. |

## Scope and attribution

This is an educational, single-transfer application using localhost. It buffers files in memory and has no congestion control, adaptive timeout, encryption, authentication or resume support. CRC32 detects packet corruption and SHA-256 checks reconstructed file contents; these checks do not authenticate a peer.

Reported byte totals count custom protocol datagrams and exclude UDP, IP and link-layer headers. The loss/delay layer operates above the operating-system socket. Results describe this controlled local setup and are not measurements of Internet performance.

The Java source and documentation were developed with ChatGPT/Codex assistance. Qwen through Ollama provides the runtime language-model interface. See [AI-ASSISTANCE.txt](AI-ASSISTANCE.txt) for the assistance record.
