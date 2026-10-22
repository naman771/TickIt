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
    authentication: "Authentication",
    payments: "Payments",
    email: "Email delivery",
  };
  document.getElementById("pageLabel").textContent = titles[section];
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
      )}</div><div class="panels"><section class="panel"><h2>Infrastructure</h2><div class="status-row"><span>PostgreSQL</span>${badge(overview.services.postgresql, "Healthy", "Unavailable")}</div><div class="status-row"><span>Redis</span>${badge(overview.services.redis, "Healthy", "Unavailable")}</div></section><section class="panel"><h2>Integrations</h2><div class="status-row"><span>Clerk authentication</span>${badge(overview.integrations.authentication)}</div><div class="status-row"><span>Razorpay checkout</span>${badge(overview.integrations.payments)}</div><div class="status-row"><span>Transactional email</span>${badge(overview.integrations.email, "Enabled", "Disabled")}</div></section></div><div class="checked-at">CHECKED AT ${escapeHtml(overview.checkedAt)}</div>`;
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
      copy: "Payment integration is pending. Customer checkout is disabled until the verified payment flow is connected.",
      items: [
        "Create server-side orders from authoritative ticket prices.",
        "Verify signatures, captured payment status and webhook authenticity.",
        "Handle duplicate events, late payments, failed bookings and refunds.",
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
