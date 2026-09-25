# Recorded Windows evidence

`runs/` is an unchanged copy of the 340 files uploaded in `assignment-runs-v7.zip`. `review-results.json` contains derived reviewer checks; it is not original engine output. The academic evaluation report has not yet been written.

Main experiment folder:

`runs/experiments-v7-20260924-013632-336-22e69d90/`

Real Ollama session:

`runs/chat-20260924-014215-613-54428eff/conversation.jsonl`

That session's transfers:

- `runs/chat-transfer-lossy-20260924-014224-928-66615368/`
- `runs/chat-transfer-delayed-20260924-014311-206-abc25a77/`

The earlier `chat-20260924-013623-577-6744a4bd/` folder is a deterministic lifecycle test. It is not the real-model session. Expected failures under `test-*` are negative protocol tests, not failed measured experiments. Both the accepted model responses and their rejected first attempts are retained.

The saved source/output binary files are not included. The original Windows audit reports checking 51 file pairs while they existed. A later archive audit can recompute recorded metrics but cannot repeat comparisons against absent received files.

To recheck the archived batch with the existing Java auditor, copy it to a scratch audit folder so its original audit report remains unchanged. From the project root in PowerShell:

```powershell
.\build.cmd
Copy-Item -Recurse .\evidence\windows-v7\runs\experiments-v7-20260924-013632-336-22e69d90 .\archived-batch-check
.\audit.cmd ".\archived-batch-check"
```

Use a fresh destination name if `archived-batch-check` already exists. The audit's reported saved-file comparison count will be zero when the original received files are unavailable. This is expected for an archived log review.

To reproduce new transfers, use `.\verify.cmd`, `.\experiments.cmd` and the real-model chat instructions in the project README. New timings may differ; retain the documented seeds, settings, files, model and environment details.
