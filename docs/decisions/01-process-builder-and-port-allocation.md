# Architectural Decisions: Phase 1 Prototype Execution Loop

## ADR 01: Subprocess ProcessBuilder vs Docker Java Client SDK

### Context
To automate cloning Git repositories and managing Docker containers, we had two implementation alternatives:
1. Include high-level Java client libraries (e.g. `docker-java`, `org.eclipse.jgit`).
2. Utilize Java's built-in `ProcessBuilder` calling standard system binaries (`git`, `docker`).

### Decision
We chose Java's built-in `ProcessBuilder` with real-time stream consumers.

### Rationale
- **Zero Heavy Dependencies:** `docker-java` brings in heavy netty and transport dependencies (~20+ JARs).
- **Parity with Developer Experience:** The logs streamed by standard `git clone` and `docker build` commands are identical to what developers see when running them locally in the terminal.
- **Troubleshooting Transparency:** Calling CLI binaries allows direct inspection and deterministic error codes.

---

## ADR 02: Dynamic Port Allocation & Conflict Prevention

### Context
Each deployed application must be accessible over HTTP on the host machine (`localhost:<port>`). Static ports create conflicts when multiple apps are deployed.

### Decision
Implement a synchronized `PortAllocationService` that:
1. Reserves a designated range (default: `9100-9200`).
2. Checks database records to prevent overlap.
3. Tests TCP socket availability (`new ServerSocket(port)`) immediately before binding.

