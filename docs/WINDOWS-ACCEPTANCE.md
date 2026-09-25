# Windows implementation and experiment review

This is an engineering review of the uploaded `assignment-runs-v7.zip`, not the final evaluation report. The Java implementation remains version 7 and has not been changed after these measurements.

## Decision

The supplied Windows evidence demonstrates the required transfer implementation, controlled experiment cases, natural-language operation, measured explanations and deterministic rejection. No implementation defect requiring a code change or rerun was found in this review. The unrestricted model prose has a documented wording limitation; numerical validation must not be described as proof of semantic correctness. This review does not predict a grade or mark the separate reports and demonstration as finished.

## Environment and provenance

| Item | Recorded value |
| --- | --- |
| Operating system | Windows 11, amd64 |
| Java | 1.8.0_422 |
| Ollama | 0.34.2 |
| Local model | qwen2.5:1.5b |
| Listed model ID | 65ec06548149 |
| Main batch | experiments-v7-20260924-013632-336-22e69d90 |
| Real chat | chat-20260924-014215-613-54428eff |
| Source identity | All 14 Java file hashes match the batch manifest |

The archive has 340 files. The full batch contains 50 measured trials, five per configuration, and one excluded warm-up. Sender seeds are 42–46; the receiver seed offset is 1000003. The input SHA-256 values, order seeds and configuration are retained in the manifest and plan.

## Verification performed

- Independently reconciled 64 completed transfers across 128 endpoint event logs, including the experiment batch, successful fault tests and chat transfers.
- Matched every one of the 50 planned experimental trials to its raw logs, endpoint summaries and combined result. No measured trial failed.
- Recomputed 496 numerical aggregate values, including means, sample standard deviations, minima and maxima, and checked sample counts and unavailable values.
- Checked useful-byte accounting, sequence-window bounds, attempts, emissions, drops, cancellations, retries, duplicate types, all eligible RTT samples, RTT statistics, goodput and overhead.
- Checked that received datagrams have corresponding outgoing emissions and that completed chat snapshots match their saved transfer summaries.
- Confirmed the archived failures belong to deliberate tests for permanent loss, exhausted DATA retries and a whole-file hash mismatch. The separate receiver-start failure is also an intentional lifecycle test.

The supplied terminal output reported 60 interface checks, 13 transfer checks and 51 adversarial/lifecycle checks: 124 in total. The archived Windows batch audit reports 51 successful source/output byte comparisons. The transferred binary files were not uploaded, so the reviewer independently checked the logs and hash consistency, not the contents of missing received files. Preserve that distinction when describing the evidence.

## Real-model acceptance

| Behavior | Evidence |
| --- | --- |
| Natural-language send | Correct lossy command, 65536-byte window; Java completed the transfer |
| Completed status | 1048576 useful bytes, 4.501828 seconds, 1.863378 Mbps |
| Live status | RUNNING, 365568 of 1048576 bytes acknowledged, 32.656747 seconds, 0.089554 Mbps |
| Delayed transfer completion | 1048576 useful bytes, 92.830532 seconds, zero retransmissions |
| Measured explanations | Two accepted responses with cited metric values after bounded retries |
| Invalid analysis rejection | Both first replies repeated a field; Java rejected them before displaying accepted analysis |
| Unsafe-command rejection | `../private.txt` rejected; no transfer followed the injected command |

The unsafe example uses `:unsafe`, a direct validator test explicitly labeled as making no model call. Do not present it as a natural-language model refusal. The actual natural-language send/status/explain records are in the real chat log.

## Explanation quality and the evidence behind it

The lossy chat transfer recorded 31 dropped DATA attempts, 36 dropped ACKs, 67 retransmission attempts and 36 duplicate DATA arrivals at the receiver. All 1048576 unique file bytes were ultimately delivered. The model's phrase "packets were lost and not delivered" must refer to the original dropped attempts; it must not be used to claim that bytes were permanently missing from the reconstructed file.

The model's second duplicate explanation correctly connects discarded ACKs to repeat DATA arrivals. The raw events support this mechanism: the per-sequence counts of dropped ACKs match the per-sequence counts of duplicate DATA in this run. For sequence 90, the receiver accepted DATA, dropped its ACK, then received duplicate DATA after the sender timed out and retried. The repeated DATA was acknowledged without adding its useful bytes twice.

Sender timestamps for that example show the original DATA attempt at 58.343 ms, timeout at 311.219 ms and retry at 311.235 ms. Receiver timestamps use a different origin; do not subtract sender and receiver timestamps to infer one-way latency.

The useful explanation was produced by the actual local model. Its raw replies and rejection history are preserved unchanged. A reviewer clarification is not a replacement model response. Unrestricted prose remains subject to human review; the Java validator proves only its declared structural and numerical checks.

## Experimental observations to preserve for later analysis

These are checks on interpretation, not a substitute for the final report:

- The short timeout produces thousands of retries without intentional loss. Missing eligible RTT samples are expected under Karn's rule because every DATA packet is retransmitted before its ACK arrives; `NA` is not zero latency.
- The wider delayed-path window improves average large-file goodput from 2.316564 to 4.191950 Mbps in this batch. These are five-trial means under the recorded local setup.
- The short timeout's large-file goodput is close to the ordinary delayed case, while emitted overhead rises from about 6.59% to 74.13%. A strong analysis should discuss the extra traffic, rather than claim that every bad timeout necessarily lowers measured goodput substantially on an unconstrained loopback path.
- One small lossy trial has zero dropped packets. That is a valid outcome of random loss, not a reason to remove the trial. The large variability must remain visible in the evaluation.
- Keep the 65536-byte-window chat run separate from the main lossy experiment's 32768-byte-window results.

## Handoff status

The package is ready for the teammate to build and inspect. It includes the tested source, Windows run instructions, a requirement checklist and the original Windows run evidence. The implementation and required experiment runs need not be repeated merely for this documentation update.

Still to complete: the final protocol specification and sequence diagram, the evaluation report using these Windows measurements, and the recorded/live demonstration. Both contributors should be able to explain the implementation and accurately disclose assistance.
