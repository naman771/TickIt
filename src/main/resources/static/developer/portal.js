"use strict";
const content = document.getElementById("portalContent");
let overview = null;
const escapeHtml = (value) =>
  String(value ?? "").replace(
    /[&<>"']/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        c
      ],
  );
const badge = (ready, good = "Connected", pending = "Not connected") =>
  `<span class="badge ${ready ? "" : "pending"}">${ready ? good : pending}</span>`;
function message(text) {
  const el = document.getElementById("accessMessage");
  if (el) el.textContent = text;
  else
    content.innerHTML = `<section class="access-panel"><div class="eyebrow">WORKSPACE ACCESS</div><h1>Access restricted.</h1><p>${escapeHtml(text)}</p></section>`;
}
async function load() {
  if (!TickItAuth.user) return;
  document.getElementById("portalAccount").innerHTML =
    '<button data-auth="sign-out">Sign out</button>';
  try {
    const token = await TickItAuth.token();
    const res = await fetch("/api/developer/overview", {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!res.ok) {
      message(
        res.status === 403
          ? "Your account does not have developer workspace access."
          : "The workspace is temporarily unavailable. Please try again later.",
      );
      return;
    }
    overview = await res.json();
    render("overview");
  } catch {
    message(
      "The workspace is temporarily unavailable. Please try again later.",
    );
  }
}
function render(section) {
  if (!overview) return;
  document
    .querySelectorAll("[data-section]")
    .forEach((el) =>
      el.classList.toggle("selected", el.dataset.section === section),
    );
  const titles = {
    overview: "Overview",
    storage: "Asset storage",
    authentication: "Authentication",
    payments: "Payments",
    email: "Email delivery",
    testing: "Pipeline testing",
  };
  document.getElementById("pageLabel").textContent = titles[section];
  if (section === "testing") {
    content.innerHTML = `<div class="section-title"><div><div class="eyebrow">CONTROLLED CONTENTION</div><h1>Pipeline testing</h1><p>Run concurrent bot attempts against isolated diagnostic data.</p></div></div><section class="panel"><h2>Component stress check</h2><p>Tests the real Redis hold script, PostgreSQL uniqueness and version conflicts, and atomic token bucket. Uses four workers and up to 32 attempts per check. One run per minute.</p><p>Diagnostic records are isolated from your catalog and bookings. This checks component invariants; it does not simulate browser traffic, payment capture, WebSocket delivery or prove production load capacity.</p><form id="diagnosticForm"><label>Attempts per check <input name="attempts" type="number" min="8" max="32" value="16" required></label> <button class="refresh" type="submit">Run bot test</button></form><div id="diagnosticResult" role="status" aria-live="polite">Loading latest result…</div></section>`;
    loadDiagnostics();
    return;
  }
  if (section === "storage") {
    content.innerHTML = `<div class="section-title"><div><div class="eyebrow">PRIVATE FILES</div><h1>Asset storage</h1><p>Private workspace files stored with Neon. Maximum 5 MB per file.</p></div>${badge(overview.services.objectStorage)}</div><section class="panel"><form id="assetUpload"><label for="assetFile">Choose a file</label> <input id="assetFile" name="file" type="file" required /><button class="refresh" type="submit">Upload file</button></form><p id="assetStatus" role="status" aria-live="polite"></p><div id="assetList">Loading files…</div><p>Showing up to 100 files. Files download as attachments.</p></section>`;
    loadAssets();
    return;
  }
  if (section === "overview") {
    content.innerHTML = `<div class="section-title"><div><div class="eyebrow">PLATFORM OPERATIONS</div><h1>Workspace overview</h1><p>Current system and integration status.</p></div><button class="refresh" data-refresh>Refresh status</button></div><div class="stats">${Object.entries(
      overview.catalog,
    )
      .map(
        ([key, value]) =>
          `<div class="stat"><span>${escapeHtml(key[0].toUpperCase() + key.slice(1))}</span><strong>${Number(value)}</strong></div>`,
      )
      .join(
        "",
      )}</div><div class="panels"><section class="panel"><h2>Infrastructure</h2><div class="status-row"><span>PostgreSQL</span>${badge(overview.services.postgresql, "Healthy", "Unavailable")}</div><div class="status-row"><span>Redis</span>${badge(overview.services.redis, "Healthy", "Unavailable")}</div><div class="status-row"><span>Neon Object Storage</span>${badge(overview.services.objectStorage, "Healthy", "Unavailable")}</div></section><section class="panel"><h2>Integrations</h2><div class="status-row"><span>Clerk authentication</span>${badge(overview.integrations.authentication)}</div><div class="status-row"><span>Razorpay checkout</span>${badge(overview.integrations.payments)}</div><div class="status-row"><span>Transactional email</span>${badge(overview.integrations.email, "Enabled", "Disabled")}</div></section></div><div class="checked-at">CHECKED AT ${escapeHtml(overview.checkedAt)}</div>`;
    return;
  }
  const details = {
    authentication: {
      title: "Identity & access",
      provider: "Clerk",
      ready: overview.integrations.authentication,
      copy: "Customer identity is verified by the backend using signed Clerk session tokens. Workspace access uses a separate server-side developer allowlist.",
      items: [
        "Email signup and Google sign-in configured in Clerk.",
        "Issuer, signature, token expiry and allowed application origin validated on the server.",
        "Customer accounts cannot access developer APIs.",
      ],
      href: "https://clerk.com/docs",
      label: "Clerk documentation",
    },
    payments: {
      title: "Payment operations",
      provider: "Razorpay",
      ready: overview.integrations.payments,
      copy: "Razorpay test checkout is implemented. Enable it only after test-account keys, capture settings and signed webhooks are configured. Live keys are blocked.",
      items: [
        "Create server-side orders from authoritative ticket prices.",
        "Verify signatures, captured payment status and webhook authenticity.",
        "Duplicate callbacks are idempotent; late captures enter review. Refund automation remains pending.",
      ],
      href: "https://razorpay.com/docs/payments/",
      label: "Razorpay documentation",
    },
    email: {
      title: "Transactional delivery",
      provider: "Resend SMTP",
      ready: overview.integrations.email,
      copy: "Email delivery requires a verified sending domain and SMTP credentials. Reliable paid-ticket delivery also needs a persistent outbox and retry worker.",
      items: [
        "Verify the sending domain and configure DNS records.",
        "Keep SMTP credentials in server-side environment variables.",
        "Send ticket notifications after the paid booking commits.",
      ],
      href: "https://resend.com/docs/send-with-smtp",
      label: "Resend SMTP documentation",
    },
  };
  const d = details[section];
  content.innerHTML = `<div class="section-title"><div><div class="eyebrow">INTEGRATIONS / ${d.provider.toUpperCase()}</div><h1>${d.title}</h1></div>${badge(d.ready)}</div><div class="integration-detail"><section class="panel"><h2>${d.provider}</h2><p>${d.copy}</p><ul>${d.items.map((item) => `<li>${item}</li>`).join("")}</ul><a class="doc-link" href="${d.href}" target="_blank" rel="noopener">${d.label} ↗</a></section></div>`;
}
document.addEventListener("click", (event) => {
  const auth = event.target.closest("[data-auth]")?.dataset.auth;
  if (auth === "sign-out") {
    TickItAuth.signOut().then(() => location.reload());
    return;
  }
  if (auth) TickItAuth.open(auth);
  const section = event.target.closest("[data-section]")?.dataset.section;
  if (section) render(section);
  if (event.target.closest("[data-refresh]")) load();
});
window.addEventListener("tickit:auth-error", (event) => message(event.detail));
TickItAuth.onChange((user) => {
  if (user) load();
  else if (overview) location.reload();
});
TickItAuth.init()
  .then(load)
  .catch((error) => message(error.message));

async function assetRequest(path = "", options = {}) {
  const token = await TickItAuth.token();
  const response = await fetch(`/api/developer/assets${path}`, {
    ...options,
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!response.ok)
    throw new Error(
      response.status === 503
        ? "Asset storage is unavailable. Please try again later."
        : "The file operation failed. Check access and the 5 MB file limit.",
    );
  return response;
}
async function loadAssets() {
  try {
    const files = await (await assetRequest()).json();
    const list = document.getElementById("assetList");
    if (!list) return;
    list.innerHTML = files.length
      ? files
          .map(
            (file) =>
              `<div class="status-row"><span>${escapeHtml(file.id)}<small> · ${Number(file.size).toLocaleString()} bytes</small></span><span><button class="refresh" data-download-asset="${escapeHtml(file.id)}">Download</button> <button class="refresh" data-delete-asset="${escapeHtml(file.id)}">Delete</button></span></div>`,
          )
          .join("")
      : "No files uploaded yet.";
  } catch (error) {
    assetStatus(error.message);
  }
}
function assetStatus(text) {
  const status = document.getElementById("assetStatus");
  if (status) status.textContent = text;
}
document.addEventListener("submit", async (event) => {
  if (event.target.id !== "assetUpload") return;
  event.preventDefault();
  const file = document.getElementById("assetFile").files[0];
  if (!file || file.size === 0 || file.size > 5 * 1024 * 1024) {
    assetStatus("Choose a file between 1 byte and 5 MB.");
    return;
  }
  const button = event.target.querySelector("button");
  button.disabled = true;
  try {
    const body = new FormData();
    body.append("file", file);
    await assetRequest("", { method: "POST", body });
    event.target.reset();
    assetStatus("File uploaded.");
    await loadAssets();
  } catch (error) {
    assetStatus(error.message);
  } finally {
    button.disabled = false;
  }
});
document.addEventListener("click", async (event) => {
  const download = event.target.closest("[data-download-asset]");
  const remove = event.target.closest("[data-delete-asset]");
  if (!download && !remove) return;
  const button = download || remove;
  button.disabled = true;
  try {
    if (remove) {
      if (!window.confirm("Delete this file permanently?")) return;
      await assetRequest(`/${encodeURIComponent(remove.dataset.deleteAsset)}`, {
        method: "DELETE",
      });
      assetStatus("File deleted.");
      await loadAssets();
    } else {
      const response = await assetRequest(
        `/${encodeURIComponent(download.dataset.downloadAsset)}`,
      );
      const url = URL.createObjectURL(await response.blob());
      const link = document.createElement("a");
      link.href = url;
      link.download = `${download.dataset.downloadAsset}.bin`;
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    }
  } catch (error) {
    assetStatus(error.message);
  } finally {
    button.disabled = false;
  }
});

let diagnosticTimer;
async function loadDiagnostics(startAttempts) {
  clearTimeout(diagnosticTimer);
  const box = document.getElementById("diagnosticResult");
  if (!box) return;
  const button = document.querySelector("#diagnosticForm button");
  if (button) button.disabled = true;
  try {
    const token = await TickItAuth.token();
    const response = await fetch("/api/developer/diagnostics", {
      method: startAttempts ? "POST" : "GET",
      headers: {
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/json",
      },
      ...(startAttempts
        ? { body: JSON.stringify({ attempts: startAttempts }) }
        : {}),
    });
    const result = await response.json();
    if (!response.ok)
      throw new Error(
        result.error || "Test could not start. Wait one minute between runs.",
      );
    box.innerHTML = `<h3>${escapeHtml(result.status)}</h3>${result.checks ? `<p>${result.attempts} attempts/check · ${result.concurrency} workers · ${result.durationMs} ms · Cleanup ${result.cleanupComplete ? "complete" : "incomplete"}</p>${result.checks.map((c) => `<div class="status-row"><span>${escapeHtml(c.name)}<br><small>${c.accepted} accepted · ${c.rejected} rejected · ${c.errors} errors · p95 ${c.p95Ms} ms</small></span>${badge(c.passed, "Passed", "Failed")}</div>`).join("")}` : ""}${result.error ? `<p>${escapeHtml(result.error)}</p>` : ""}`;
    if (result.status === "RUNNING")
      diagnosticTimer = setTimeout(() => loadDiagnostics(), 1500);
    else if (button) button.disabled = false;
  } catch (error) {
    box.textContent = error.message;
    if (button) button.disabled = false;
  }
}
document.addEventListener("submit", (event) => {
  if (event.target.id !== "diagnosticForm") return;
  event.preventDefault();
  loadDiagnostics(Number(new FormData(event.target).get("attempts")));
});
