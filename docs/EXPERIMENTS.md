# Measurement and experiment methods

This document describes the implementation and reproducible experiment method. It is not the final evaluation report.

## Timing and byte accounting

Sender timing starts when its Metrics instance is created, after input reading but before socket setup and START. It ends after a valid DONE with the expected SHA-256. It includes metadata negotiation, transfer and completion. Receiver timing starts before waiting for START and ends at file verification. Do not directly compare the two elapsed times or subtract timestamps from different endpoints: their origins differ.

`payload_bytes` at the sender counts unique acknowledged file bytes. At the receiver it counts unique accepted file bytes. Retransmissions and duplicates do not add useful bytes. The combined harness also independently compares source and output bytes.

| Metric | Definition |
| --- | --- |
| `goodput_mbps` | Unique useful bytes × 8 / sender elapsed seconds / 1,000,000 |
| `emitted_udp_mbps` | This endpoint's emitted custom datagram bytes × 8 / elapsed seconds / 1,000,000 |
| `packets_attempted` | Every request to the outgoing impairment shim, including retries and dropped requests |
| `packets_emitted` | Actual socket sends after the shim |
| `packets_received` | Datagrams arriving at this endpoint, including invalid datagrams |
| `packets_acknowledged` | Sender's accepted READY, distinct DATA ACKs and DONE |
| `retransmission_ratio` | Retransmitted request attempts / all sender attempts, including START and FIN |
| `emitted_overhead_share` | (Both endpoints' emitted custom datagram bytes − file bytes) / both endpoints' emitted bytes |
| `attempted_overhead_share` | Same formula using attempted bytes, including packets dropped by the shim |
| `rtt_mean_ms` | Mean of eligible DATA RTT samples |
| `rtt_p95_ms` | Sorted sample at index ceil(0.95 × n) − 1 |
| `rtt_variation_ms` | Mean absolute difference between successive eligible RTT samples, in ACK arrival order |

Each encoded datagram contains a 32-byte header, payload and 4-byte CRC. Byte accounting excludes UDP, IP and link headers. The useful payload is subtracted once; repeated payload, custom headers, acknowledgements and control traffic all contribute to overhead. Dropped attempts were never emitted, so the attempted and emitted denominators answer different questions.

`RTT_SAMPLE` events save each eligible sample to six decimal places in milliseconds (nanosecond resolution). Samples begin at the sender's send attempt, include shim delay on both legs and local processing, and end when the ACK is processed. Karn's rule excludes retransmitted DATA because the ACK's corresponding send is ambiguous. Empty files have no DATA RTT; unavailable results are `NA`. The RTT variation statistic is a delay-variation proxy, not a one-way network-jitter measurement. Operating-system scheduling adds variation beyond the configured shim delay.

## Loss, duplicates and cancellation

- `dropped_data`: sender DATA discarded by the shim.
- `receiver_dropped_acks`: ACKs discarded by the receiver's outgoing shim.
- `receiver_duplicate_data`: repeated DATA accepted as duplicates and acknowledged again.
- `duplicate_acks`: repeat ACKs observed at the sender.
- `receiver_out_of_order_data`: first arrivals whose sequence exceeds the next missing sequence.
- `CANCEL` events: scheduled packets still queued when an endpoint closes; they were attempted but never emitted.

A lost ACK can cause a duplicate DATA arrival even when the original DATA arrived intact. A timeout alone does not prove loss: a short timeout can expire before a delayed ACK arrives. For the no-loss delayed experiment, extra retries and duplicates support a premature-timeout interpretation; distinguish that controlled observation from a general claim about all networks.

The 3% loss setting applies independently to outgoing packets from both endpoints. It does not mean exactly 3% will be dropped in every trial or that the file loses 3% of its bytes. A short trial can see no drops. Retries repair the losses. Compare observed sender/receiver drop counts with attempts and report the configured probability separately.

## Design of the batch

The test inputs are Java Random fixtures: 32768 bytes with seed 11 and 1048576 bytes with seed 12. The harness checks their bytes before running, so a modified fixture cannot silently change the experiment. It preserves user files and asks you to rename a modified fixture before regenerating it.

All configurations use chunk size 1024 and at most 20 retransmissions per request. Default window is 32768 bytes; default timeout is 250 ms. Delayed traffic uses 25 ms plus a uniform integer 0..25 ms on each outgoing packet. Scheduled sends permit reordering and do not sleep the sender thread. There is no link-capacity/bandwidth emulator or background cross traffic.

The ten cases are listed in README. The five repetitions use sender seeds 42..46; each receiver uses sender seed + 1000003. Order within repetition r is shuffled with seed 42000 + r. One baseline large-file warm-up is retained separately but excluded from every aggregate. A warm-up reduces some first-use effects; it does not prove all JIT, cache and operating-system effects are gone.

Pair short-timeout small with delayed small, short-timeout large with delayed large, narrow-window small with delayed small and wide-window large with delayed large. This preserves file size and impairment settings while changing one protocol setting. Equal seeds do not produce identical packet fault locations across configurations because retries change the random draw sequence.

Use all five trials. Report mean and sample standard deviation (n − 1 denominator), with raw results available. Do not choose the fastest baseline run or omit an inconvenient failed trial. Five repetitions show run variation; they do not justify strong statistical or Internet-wide claims. Baseline small-file throughput is especially sensitive to startup and scheduling.

The harness records failures in `all-results.csv` and the manifest; a partial batch remains identifiable. Its aggregate excludes failed trials but explicitly reports successful and failed counts. Treat failures as results to investigate, not as permission to claim successful completion. The final audit refuses failed or incomplete batches.

## Evidence review before reporting

1. Check the manifest says COMPLETED and all 50 measured trials succeeded.
2. Check `audit-report.txt`: 51 completed transfers, 102 endpoint logs and 50 planned trials. This includes the excluded warm-up.
3. Inspect all-results and aggregate for unexpected variation, loss-free lossy trials and empty RTT samples under premature timeouts.
4. Check original events for representative baseline, lossy and delayed trials. Keep both endpoints' files.
5. Run and retain real-model chat evidence on the submission laptop. Confirm that the explanation is useful and accurate, not merely valid JSON.
6. Clearly identify the platform for each set of results. Historical tests and current Windows experiments must not be mixed into one unlabeled average.

Recommended improvement to investigate in the later report: an adaptive retransmission timeout based on valid RTT samples, with backoff. The current implementation intentionally retains a fixed timeout so the experiments directly expose its tradeoffs; no unimplemented adaptive algorithm should be claimed as present.
