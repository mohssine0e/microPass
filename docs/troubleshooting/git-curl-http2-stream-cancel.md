# Troubleshooting: Git RPC Failed / curl 92 HTTP/2 Stream CANCEL & False User Cancellation

## Symptom
During repository cloning (`git clone`), the deployment logs show:
```
error: RPC failed; curl 92 HTTP/2 stream 5 was not closed cleanly: CANCEL (err 8)
error: 16389 bytes of body are still expected
fetch-pack: unexpected disconnect while reading sideband packet
fatal: early EOF
fatal: fetch-pack: invalid index-pack output
🛑 [CANCELLED] Deployment stopped and cancelled by user.
```
Even though the developer **never clicked "Cancel"**.

---

## Dual Root Cause Analysis

### 1. The Network / Git Protocol Cause (`curl 92 HTTP/2 CANCEL`)
- Git uses `libcurl` for HTTP(S) data transfers. By default, modern Git negotiates **HTTP/2**.
- When cloning medium-to-large repositories or over networks with packet jitter or latency fluctuations, the remote Git host (GitHub, GitLab, or intermediate CDN/proxy edge nodes) sends an HTTP/2 `RST_STREAM` frame with error code `0x8` (`CANCEL`).
- In RFC 7540 Section 7, HTTP/2 error `0x8` is defined as `CANCEL` (indicating the endpoint terminated the stream).
- When `libcurl` receives this frame while expecting more payload bytes (`16389 bytes of body are still expected`), it aborts with exit code 92 and translates the protocol error code `0x8 CANCEL` as `stream was not closed cleanly: CANCEL (err 8)` or `stream 5 reset by user, error 0x8 cancel`.
- **"User" and "CANCEL" in this error string are curl/HTTP2 protocol terminologies, NOT the human user!**

### 2. The microPass False Positive Cause
Earlier versions of `DeploymentPipelineService` checked whether an exception was due to a user cancellation using:
```java
boolean isUserCancel = (cancellationFlag != null && cancellationFlag.get())
        || (e.getMessage() != null && e.getMessage().toLowerCase().contains("cancel"));
```
Because curl's error text contained the word `"cancel"`, `e.getMessage().toLowerCase().contains("cancel")` evaluated to `true`. microPass falsely reported that the developer cancelled the deployment and marked it `CANCELLED`.

---

## Solutions & Permanent Fix

### 1. Enforce HTTP/1.1 and High Buffer Sizes for Git
HTTP/1.1 uses persistent single TCP streams without HTTP/2 stream multiplexing, eliminating `RST_STREAM` (`0x8 CANCEL`) drops. In `GitService.java`:
```java
List<String> cloneCmd = List.of(
        "git",
        "-c", "http.version=HTTP/1.1",
        "-c", "http.postBuffer=1048576000",
        "-c", "http.lowSpeedLimit=1000",
        "-c", "http.lowSpeedTime=60",
        "clone",
        "--depth", "1",
        "--progress",
        "--branch", branch,
        gitUrl,
        targetDir.toAbsolutePath().toString()
);
```

### 2. Automatic Retry Loop on Network Drops
If an unexpected network drop occurs during cloning, `GitService` automatically cleans the destination directory and retries once with a 2-second backoff. If the user actually cancelled, the retry loop immediately aborts without re-running.

### 3. Type-Safe Exception Handling (`DeploymentCancelledException`)
We eliminated ambiguous string matching. User cancellation is now strictly identified by:
1. `cancellationFlag.get() == true` (the `AtomicBoolean` set exclusively by the cancellation API).
2. `e instanceof DeploymentCancelledException` (thrown only when `cancellationFlag.get()` is true).

Any external tool error containing the word "cancel" is now correctly classified as `FAILED`, and the developer is shown the actual root error instead of a false cancellation message.
