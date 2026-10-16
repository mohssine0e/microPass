# Phase 3: Developer Dashboard & Full MVP Verification

## 1. Goal of this Phase
Deliver the developer user interface and achieve full MVP completion:
1. **Interactive Web Dashboard:** Clean, responsive UI built directly inside the Spring monolith using **Thymeleaf + HTMX + Tailwind CSS**.
2. **Dynamic Partial Swaps (HTMX):** Registering applications, stopping containers, and refreshing the app list happens asynchronously via HTML fragments with zero full-page reloads.
3. **Live SSE Terminal Modal:** Real-time log console integrated directly into the browser via Server-Sent Events, complete with auto-scroll and status indicators.

---

## 2. Theoretical Concepts & Why HTMX + Thymeleaf?

### Why Not React, Next.js, or Angular for microPass?
1. **Zero Node.js Build Toolchain Overhead:**
   - Single-Page Applications (SPAs) require `npm`, `webpack`/`vite`, package lockfiles, and gigabytes of `node_modules`.
   - On a student development environment with resource limits, maintaining two separate build runtimes (Node.js + Java Maven) doubles complexity and memory footprint.
2. **Server-Driven HTML Architecture:**
   - With Thymeleaf and HTMX, the Spring Boot application renders and returns pure HTML fragments.
   - HTMX swaps only the modified DOM node (`hx-target="#apps-table-container"` with `hx-swap="outerHTML"`).
   - This provides the snappy, single-page application feel of React with 0 KB of client bundle build steps.

---

## 3. Work Done in Phase 3

1. **Thymeleaf Dependency:**
   - Added `spring-boot-starter-thymeleaf` to [`pom.xml`](file:///home/mohssine/Desktop/projects/microPass/pom.xml).
2. **Dashboard Controller ([`DashboardViewController.java`](file:///home/mohssine/Desktop/projects/microPass/src/main/java/com/micropass/web/DashboardViewController.java)):**
   - `GET /ui`: Serves the primary application layout and app list.
   - `GET /ui/apps/table`: Returns the dynamic table fragment (`dashboard :: appsTable`).
   - `POST /ui/apps`: Registers a new project and returns the updated table fragment directly to HTMX.
   - `POST /ui/apps/{id}/stop`: Gracefully stops the container and returns the refreshed table fragment.
3. **Template ([`dashboard.html`](file:///home/mohssine/Desktop/projects/microPass/src/main/resources/templates/dashboard.html)):**
   - Styled with Tailwind CSS (dark mode theme).
   - Live Terminal Modal using the browser's native `EventSource` connected directly to `/api/deployments/{id}/logs/stream`.
   - Dynamic status badges (`Running`, `Stopped`) and clickable port URLs (`localhost:9100`).

---

## 4. End-to-End MVP Verification

1. **Dashboard Loading:** Verified `GET http://localhost:9090/ui` returns HTTP 200 with rendered dashboard.
2. **App Registration via HTMX:** Verified `POST http://localhost:9090/ui/apps` dynamically updates and renders registered apps without page refreshes.
3. **Deployment Streaming:** One-click deployment triggers the async engine and streams logs live to the modal terminal window.
4. **Active Probing & Verification:** Deployed container responds cleanly on its assigned port, tracked in the dashboard.

