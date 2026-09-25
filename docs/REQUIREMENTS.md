# Assignment requirement audit — version 7

Source of requirements: the supplied “Assignment: LLM Assisted Reliable File Transfer”, COSC 630/788, four-page project specification. This checklist records acceptance against the uploaded Windows version 7 evidence. It distinguishes implementation from the final written and recorded deliverables. It is not a predicted grade.

| Requirement | Implementation/evidence | Acceptance status |
| --- | --- | --- |
| Java sender and receiver using UDP; no existing reliable-transfer library | `Transfer`, `Link`; Java standard library only; combined harness over real UDP sockets; separate-process launchers | Windows harness and protocol tests passed; standalone launchers available |
| Filename, size, transfer ID and negotiated chunk handshake | START metadata; READY chunk response; metadata validation before allocating state | Verified, including invalid metadata and negotiation tests |
| Sequence, payload length, integrity and transfer ID | `Packet`: binary framing and CRC32 | Verified with malformed/modified datagrams |
| ACKs, timeout, retransmit, duplicate handling, clean completion | Selective repeat; bounded START/DATA/FIN retries; duplicate DATA ACKs; retained DONE responses | Verified with each message type dropped and with exhausted retry budget |
| Corruption and whole-file integrity | Per-datagram CRC32; final SHA-256; harness byte comparison | Verified, including deliberately wrong content with a valid packet CRC |
| Natural-language start | `ChatConsole`, local Ollama, strict `Command` validation | Passed: actual version 7 lossy and delayed commands |
| Natural-language status during/after transfer | Background worker and synchronized engine snapshots; average goodput so far | Passed: completed status and RUNNING status with 365568 acknowledged bytes |
| Post-transfer model explanation citing measurements | Frozen snapshot, bounded schema, numeric references, retry and raw audit trail | Demonstrated: cited values, retry validation and supported lost-ACK explanation; prose limitation documented |
| Java controls paths, endpoints, bounds and lifecycle | Basename and real-path checks; receiver A / localhost:9000; bounded window, timeout, retries; one active transfer | Verified by validators, wire and lifecycle tests; arbitrary rate/address commands are unsupported |
| Model has no direct file/socket/shell tools or credentials | Fixed localhost HTTP request with no tools or transferred file contents; fixed Java dispatch | Verified in source and HTTP fixture |
| CSV/JSONL raw events and completed summaries | Both endpoint event CSVs, summaries and chat JSONL | 102 endpoint logs audited for the complete experiment batch |
| Size, useful bytes, time and throughput | Engine counters and monotonic sender timing | Recomputed from uploaded logs; original Windows run reports byte comparisons |
| Sent/received/ACK/timeout/duplicate/retry counts | Raw events and endpoint summaries; duplicates and drops separated by packet type | Reconciled against raw events |
| Retry ratio and protocol overhead | Explicit denominators and both-direction custom datagram byte totals | Recomputed; definitions in EXPERIMENTS.md |
| RTT mean and percentile, configuration and scenario | Individual eligible RTT samples, mean, nearest-rank p95 and variation proxy; full configuration | Recomputed; Karn exclusions tested; unavailable RTT remains NA |
| Small and large baseline, lossy ≥2%, delayed/variable cases | Both sizes in baseline, 3% loss, 25–50 ms per-leg delay | Five trials per required case completed on Windows |
| Timeout/window comparisons | Two short-timeout cases plus narrow/wide window comparisons | Five trials each, changing one setting against a matched delayed case |
| Reproducible experiments and raw evidence | Plan, seeds, input/source hashes, incremental results, failure counts, aggregates and audit | 50 measured Windows trials + excluded warm-up; original logs included in evidence/windows-v7 |
| Source and build/run instructions | README, Windows scripts and Java 8 source | Windows build/verify/experiments completed on Java 1.8.0_422 |
| At least three raw logs with reproduction commands | Complete experimental archive; `experiments.cmd` and per-case plan | Complete Windows experiment logs included with reproduction commands |
| Protocol document, 2–4 pages, and sequence diagram | Existing working protocol notes/diagram are starting material | Final document and diagram review still to complete |
| Evaluation report, 4–6 pages | Raw/aggregate results and method now ready for analysis | Still to complete using the verified Windows measurements |
| Recorded/live demonstration, 5–8 minutes | README gives natural-language send, status, loss, explanation and unsafe-rejection steps | Recording/live assessment still required |
| AI/tool disclosure and ability to explain code | AI-ASSISTANCE.txt and CODE-WALKTHROUGH.txt | Students must review and supply accurate final disclosure |

## Acceptance result

The supplied Windows output reports all 124 Java checks passed. The uploaded archive contains all 50 measured trials, the excluded warm-up and actual local-model chat. Independent review reconciled the completed-transfer logs, aggregate values and source hashes. See [Windows acceptance review](WINDOWS-ACCEPTANCE.md) for the detailed scope and limitations.

No Java source changes or experiment reruns were required after this review. The implemented behavior and required experiment runs have been demonstrated. The model's loss wording needs human interpretation: original packet attempts were dropped, but the complete file was recovered. Preserve the actual model response and document this limitation; do not claim general semantic verification.

The original transferred binary files were not uploaded. The reviewer verified the recorded events and hash consistency; the source/output byte comparisons are evidenced by the Windows engine and its original audit report. Final reports, diagram and the required recorded/live demonstration remain separate work.
