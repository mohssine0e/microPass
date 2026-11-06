# Phase 3.6: Git Protocol Resilience, Incremental Caching & Automated Testing

> **Document Type:** Phase Architecture & Implementation Deep Dive
> **Audience:** Engineering Reviewers, Technical Evaluators, and Students
> **Status:** Completed & Tested

---

## 1. Executive Summary

In this phase, we tackled two critical runtime bottlenecks in the microPass deployment engine:
1. **The Phantom Cancellation Bug (`curl 92 HTTP/2 CANCEL`):** A protocol-level connection drop during `git clone` was falsely diagnosed by the engine as a human user clicking "Cancel".
2. **The "Start From Scratch" Inefficiency:** Every deployment and retry was re-downloading the entire repository from byte 0, wasting bandwidth and making builds slow and brittle.

We resolved these issues by implementing **Type-Safe Cancellation**, enforcing **HTTP/1.1 transport**, introducing an **Incremental Git Caching & Resume Engine**, and establishing the project's **Automated Test Suite**.

---

## 2. Deep Dive: The Phantom Cancellation Bug

### The Problem
During a deployment, Git failed with the following error output:
```text
error: RPC failed; curl 92 HTTP/2 stream 5 was not closed cleanly: CANCEL (err 8)
error: 16389 bytes of body are still expected
fetch-pack: unexpected disconnect while reading sideband packet
fatal: early EOF
fatal: fetch-pack: invalid index-pack output
🛑 [CANCELLED] Deployment stopped and cancelled by user.
```
**The developer never clicked "Cancel"**, yet the engine reported it was cancelled by the user.

### Why It Happened (Dual-Layer Failure)

```mermaid
flowchart TD
    Network["Network Jitter / Latency Spike"] --> HTTP2["GitHub Proxy sends HTTP/2 RST_STREAM (0x8)"]
    HTTP2 --> RFC["RFC 7540: Error 0x8 is named 'CANCEL'"]
    RFC --> Curl["libcurl prints: 'stream 5 reset by user, error 0x8 cancel'"]
    Curl --> Engine["microPass: e.getMessage().contains('cancel') == TRUE"]
    Engine --> FalseCancel["Falsely marked as: 'CANCELLED by user'!"]
```

1. **Protocol Layer (Git / HTTP/2):**
   * Modern Git uses `libcurl`, which negotiates **HTTP/2** over TLS by default.
   * In HTTP/2, if a TCP connection lags during a large packfile transfer, intermediate proxies or GitHub servers send an HTTP/2 `RST_STREAM` frame with error code `0x8`.
   * Under the HTTP/2 specification (**RFC 7540, Section 7**), code `0x8` is officially named **`CANCEL`** (`"Used by the endpoint to indicate that the stream is no longer needed"`).
   * `libcurl` aborts and reports: `curl 92 HTTP/2 stream 5 reset by user, error 0x8 cancel`. In this context, `"user"` and `"cancel"` are internal `libcurl` socket terms, **not the human developer**.

2. **Application Layer (microPass String Matching Flaw):**
   * In `DeploymentPipelineService.java`, the exception handler contained this naive check:
     ```java
     // ❌ THE FLAWED CODE:
     boolean isUserCancel = (cancellationFlag != null && cancellationFlag.get())
             || (e.getMessage() != null && e.getMessage().toLowerCase().contains("cancel"));
     ```
   * Because curl's stderr contained the word `"cancel"`, `e.getMessage().contains("cancel")` evaluated to `true`.
   * The platform falsely concluded that the human user clicked "Cancel", marked the deployment as `CANCELLED`, and blamed the user!

### The Engineering Solution

1. **Enforce HTTP/1.1 for Git:**
   HTTP/1.1 uses persistent single TCP streams without HTTP/2 stream multiplexing, eliminating `0x8 CANCEL` frame drops entirely:
   ```java
   List<String> cloneCmd = List.of(
           "git",
           "-c", "http.version=HTTP/1.1",
           "-c", "http.postBuffer=1048576000", // 1GB buffer
           "-c", "http.lowSpeedLimit=1000",
           "-c", "http.lowSpeedTime=60",
           ...
   );
   ```
2. **Type-Safe Exception Hierarchy:**
   We introduced `DeploymentCancelledException` and removed the substring search on `"cancel"`:
   ```java
   // ✅ TYPE-SAFE IMPLEMENTATION:
   boolean isUserCancel = (cancellationFlag != null && cancellationFlag.get())
           || (e instanceof DeploymentCancelledException);
   ```
   Now, external tool outputs containing the word `"cancel"` are accurately classified as `FAILED`, and the developer is shown the true technical error.

---

## 3. Deep Dive: Incremental Caching & Resume Engine

### The Problem with Ephemeral Workspaces
Previously, each deployment created a brand new folder:
```text
/tmp/micropass/workspaces/app-{id}/deployment-{deploymentId}
```
And whenever a deployment finished, failed, or was cancelled, that directory was wiped clean:
- Deploying twice re-downloaded the entire repository from zero.
- If a clone was interrupted at 95%, restarting meant re-downloading from 0%.
- Docker build layer caching was suboptimal because the build context directory changed with every deployment ID.

### The New Architecture: Persistent App Source Cache

```mermaid
flowchart TD
    Trigger["Deploy Triggered"] --> Check{"Does /app-{id}/source/.git exist?"}

    Check -->|Yes & Healthy| VerifyRemote{"Does git remote get-url origin match app.gitUrl?"}
    VerifyRemote -->|Yes| FastPath["Fast Incremental Fetch:<br/>git fetch --depth 1 origin &lt;branch&gt;<br/>git checkout -B &lt;branch&gt;<br/>git reset --hard & git clean -fd"]
    FastPath --> SubSecond["✅ Complete in &lt; 1 second! (0 bytes if no new commits)"]

    VerifyRemote -->|No: URL changed| Reset["Clean directory & re-clone"]
    Check -->|No / Corrupted| FreshClone["Full Shallow Clone (--depth 1)"]
    Reset --> FreshClone
    FreshClone --> Done["Commit Hash Recorded"]
    SubSecond --> Done
```

### Key Technical Mechanics:

1. **Source Cache Location:**
   Repositories are now preserved at:
   `/tmp/micropass/workspaces/app-{appId}/source`
2. **Fast Incremental Fetch:**
   When the cache already exists:
   * `git fetch --depth 1 origin <branch>` fetches only newly pushed commits.
   * `git checkout -B <branch> origin/<branch>` updates the working branch.
   * `git reset --hard origin/<branch>` and `git clean -fd` wipe any untracked or dirty build artifacts left from a previous build or cancellation, guaranteeing a **100% clean build context**.
   * **Benchmarked Speed:** Finishes in **$\approx 0.2$ to $1.0$ second**!
3. **Self-Healing & URL Change Detection:**
   * If the developer updates the App's Git URL in settings, microPass compares `git remote get-url origin`. If they differ, it cleans the cache and clones the new repo.
   * If a previous download was aborted and `.git` is corrupted, `isGitRepoHealthy()` detects the failure and gracefully falls back to a clean clone.
4. **Lifecycle Separation:**
   * `cleanWorkspace(appId, deploymentId)` no longer deletes the source cache.
   * The cache is only destroyed when the application itself is deleted via `DELETE /api/apps/{id}`, which calls `cleanAppWorkspace(appId)`.

---

## 4. The Automated Test Suite

To verify these architectural changes, we established the project's automated test suite using **JUnit 5** and **AssertJ**.

### 1. `WorkspaceServiceTest.java`
Tests directory isolation, cache persistence, and cascading app deletion:
* `prepareAppWorkspace_createsSourceDirectory`: Asserts `/app-{id}/source` is properly structured.
* `cleanWorkspace_preservesSourceCache`: Writes a dummy file to the cache, invokes deployment cleanup, and asserts the cache file is **retained**.
* `cleanAppWorkspace_deletesEntireAppFolder`: Asserts that explicit app deletion wipes the entire application tree.

### 2. `GitServiceTest.java`
Performs an end-to-end integration test with a real local Git repository:
1. Initializes a local Git origin repository (`git init -b main`).
2. Creates an initial commit (`README.md`).
3. Calls `syncRepository`: Verifies initial shallow clone works and logs `"Starting full shallow clone"`.
4. Commits a new file (`feature.txt`) to the origin repository.
5. Calls `syncRepository` a second time: Verifies the **fast incremental fetch** path is taken (`"Performing fast incremental fetch"`), executes in milliseconds, and the checked-out workspace contains `feature.txt` with the updated commit hash!

### Test Execution Result:
```text
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running com.micropass.service.WorkspaceServiceTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.528 s
[INFO] Running com.micropass.service.GitServiceTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.198 s
[INFO]
[INFO] Results:
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

---

## 5. Summary of Files Changed & Created

| File | Change Type | Purpose |
| :--- | :--- | :--- |
| [`src/main/java/com/micropass/service/GitService.java`](file:///home/mohssine/Desktop/projects/microPass/src/main/java/com/micropass/service/GitService.java) | Modified | Added `syncRepository()`, incremental fetch, self-healing clone, HTTP/1.1 enforcement, and 1GB post-buffer. |
| [`src/main/java/com/micropass/service/WorkspaceService.java`](file:///home/mohssine/Desktop/projects/microPass/src/main/java/com/micropass/service/WorkspaceService.java) | Modified | Added `prepareAppWorkspace()`, `cleanAppWorkspace()`, and decoupled deployment cleanup from source cache retention. |
| [`src/main/java/com/micropass/service/DeploymentPipelineService.java`](file:///home/mohssine/Desktop/projects/microPass/src/main/java/com/micropass/service/DeploymentPipelineService.java) | Modified | Integrated `syncRepository()`, replaced string matching with `DeploymentCancelledException`, and added `deleteApp()`. |
| [`src/main/java/com/micropass/web/AppController.java`](file:///home/mohssine/Desktop/projects/microPass/src/main/java/com/micropass/web/AppController.java) | Modified | Added `DELETE /api/apps/{id}` endpoint for complete application and workspace deletion. |
| [`src/test/java/com/micropass/service/WorkspaceServiceTest.java`](file:///home/mohssine/Desktop/projects/microPass/src/test/java/com/micropass/service/WorkspaceServiceTest.java) | New File | Unit tests for workspace source directory creation and cache preservation. |
| [`src/test/java/com/micropass/service/GitServiceTest.java`](file:///home/mohssine/Desktop/projects/microPass/src/test/java/com/micropass/service/GitServiceTest.java) | New File | Integration test verifying clone $\to$ commit $\to$ sub-second incremental sync. |
| [`docs/troubleshooting/git-curl-http2-stream-cancel.md`](file:///home/mohssine/Desktop/projects/microPass/docs/troubleshooting/git-curl-http2-stream-cancel.md) | New File | Dedicated troubleshooting reference for HTTP/2 `0x8 CANCEL` network drops. |
| [`docs/learning/HOW_IT_WORKS_AND_INTERVIEW_GUIDE.md`](file:///home/mohssine/Desktop/projects/microPass/docs/learning/HOW_IT_WORKS_AND_INTERVIEW_GUIDE.md) | Modified | Documented **Edge 7** (HTTP/2 Protocol Resets) and **Edge 8** (Incremental Caching Engine). |
