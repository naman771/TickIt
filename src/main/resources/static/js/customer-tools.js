"use strict";
let savedEvents = new Set(),
  savedFor = null,
  savedLoad = null,
  onlySaved = false;
function resetCustomerTools() {
  savedEvents = new Set();
  savedFor = null;
  savedLoad = null;
  onlySaved = false;
  state.ticketEvents = [];
  document.getElementById("customerToolDialog")?.remove();
}
async function loadSavedEvents() {
  const user = state.user?.id;
  if (!user) return;
  if (savedFor === user) return;
  if (savedLoad) return savedLoad;
  savedLoad = (async () => {
    try {
      const ids = await api("/api/account/saved-events");
      if (state.user?.id !== user) return;
      savedEvents = new Set(ids);
      savedFor = user;
      refreshSavedButtons();
      if (state.view === "events") renderEvents();
    } catch {
      /* Saving controls report errors on interaction; catalog remains available. */
    } finally {
      savedLoad = null;
    }
  })();
  return savedLoad;
}
function saveEventButton(e) {
  return `<button class="secondary-button" data-save-event="${Number(e.id)}" aria-pressed="${savedEvents.has(e.id)}">${savedEvents.has(e.id) ? "Saved" : "Save event"}</button>`;
}
function refreshSavedButtons() {
  document.querySelectorAll("[data-save-event]").forEach((b) => {
    const saved = savedEvents.has(Number(b.dataset.saveEvent));
    b.textContent = saved ? "Saved" : "Save event";
    b.setAttribute("aria-pressed", String(saved));
  });
}
function ticketBucket(t) {
  const e = t.event || state.events.find((e) => e.id === t.eventId);
  if (
    ["CANCELLED", "DELETED"].includes(e?.status) ||
    t.refund ||
    t.status === "INACTIVE"
  )
    return "cancelled";
  const end = e?.endInstant || e?.startInstant;
  return end && Date.parse(end) < Date.now() ? "past" : "upcoming";
}
function ticketTools(e, order, t) {
  return `${venueLink(e)} ${calendarLink(e)} ${t?.ticketId && t.status === "VALID" && Date.parse(e.startInstant) > Date.now() && !t.refund && !["CANCELLED", "DELETED"].includes(e.status) ? `<button class="secondary-button" data-offer-transfer="${esc(t.ticketId)}">Transfer ticket</button>` : ""} ${order ? `<button class="secondary-button" data-order-help="${esc(order)}">Get help</button>` : ""}`;
}
function enhanceTicketDashboard(tickets, orders) {
  state.ticketEvents = tickets
    .map((t) => t.event || state.events.find((e) => e.id === t.eventId))
    .filter(Boolean);
  state.ticketOrders = orders;
  const list = document.querySelector(".tickets-list:not([aria-label])");
  if (!list) return;
  loadSavedEvents();
  list.insertAdjacentHTML(
    "beforebegin",
    `<div class="ticket-filters" role="group" aria-label="Filter tickets">${[
      ["all", "All tickets"],
      ["upcoming", "Upcoming"],
      ["past", "Past"],
      ["cancelled", "Cancelled / inactive"],
    ]
      .map(
        ([key, label]) =>
          `<button class="filter ${key === "all" ? "active" : ""}" data-ticket-filter="${key}" aria-pressed="${key === "all"}">${label}</button>`,
      )
      .join(
        "",
      )}</div><p id="ticketFilterStatus" role="status"></p><section id="ticketTransfers" class="customer-support" aria-label="Transfers and received tickets"></section><section class="customer-support"><button class="secondary-button" data-support-history>My support requests</button></section>`,
  );
  loadTicketTransfers();
}
function customerDialog(title, body) {
  document.getElementById("customerToolDialog")?.remove();
  const dialog = document.createElement("dialog");
  dialog.id = "customerToolDialog";
  dialog.className = "admission-dialog";
  dialog.innerHTML = `<button class="quiet-button ticket-close" data-close-customer>Close ×</button><h2>${esc(title)}</h2>${body}<p id="customerToolStatus" role="status"></p>`;
  document.body.append(dialog);
  dialog.addEventListener("close", () => dialog.remove());
  dialog.showModal();
  return dialog;
}
async function supportHistory() {
  const dialog = customerDialog("Your support requests", "<p>Loading…</p>");
  try {
    const rows = await api("/api/account/support");
    if (!dialog.isConnected) return;
    dialog.querySelector("p").outerHTML = rows.length
      ? rows
          .map(
            (r) =>
              `<article class="support-message"><strong>${esc(r.category)} · ${esc(r.status)}</strong><small>Order ${esc(r.order_id)}</small><p>${esc(r.message)}</p>${r.reply ? `<p><strong>TickIt reply</strong><br>${esc(r.reply)}</p>` : "<p>Your request is waiting for review.</p>"}</article>`,
          )
          .join("")
      : "<p>You have no support requests.</p>";
  } catch (error) {
    if (dialog.isConnected)
      dialog.querySelector("p").textContent = error.message;
  }
}
document.addEventListener("click", async (event) => {
  if (event.target.closest("[data-close-customer]"))
    document.getElementById("customerToolDialog")?.close();
  if (event.target.closest("[data-support-history]")) supportHistory();
  const save = event.target.closest("[data-save-event]");
  if (save) {
    if (!state.user) {
      TickItAuth.open("sign-in");
      return;
    }
    save.disabled = true;
    const user = state.user.id,
      id = Number(save.dataset.saveEvent);
    try {
      await loadSavedEvents();
      const was = savedEvents.has(id);
      await api(`/api/account/saved-events/${id}`, {
        method: was ? "DELETE" : "PUT",
      });
      if (state.user?.id !== user) return;
      was ? savedEvents.delete(id) : savedEvents.add(id);
      refreshSavedButtons();
      if (state.view === "events") renderEvents();
    } catch (error) {
      notify(error.message, true);
    } finally {
      save.disabled = false;
    }
  }
  const savedOnly = event.target.closest("[data-saved-only]");
  if (savedOnly) {
    if (!state.user) {
      TickItAuth.open("sign-in");
      return;
    }
    await loadSavedEvents();
    onlySaved = !onlySaved;
    savedOnly.setAttribute("aria-pressed", String(onlySaved));
    savedOnly.textContent = onlySaved ? "Showing saved events" : "Saved events";
    renderEvents();
  }
  const filter = event.target.closest("[data-ticket-filter]");
  if (filter) {
    const key = filter.dataset.ticketFilter;
    document.querySelectorAll("[data-ticket-filter]").forEach((b) => {
      b.classList.toggle("active", b === filter);
      b.setAttribute("aria-pressed", String(b === filter));
    });
    let n = 0;
    document.querySelectorAll("[data-ticket-bucket]").forEach((card) => {
      card.hidden = key !== "all" && card.dataset.ticketBucket !== key;
      if (!card.hidden) n++;
    });
    document.getElementById("ticketFilterStatus").textContent =
      `${n} ticket${n === 1 ? "" : "s"}`;
  }
  const help = event.target.closest("[data-order-help]");
  if (help) {
    const id = help.dataset.orderHelp,
      o = state.ticketOrders?.find((o) => o.id === id);
    customerDialog(
      "Booking help",
      `<p>Order ${esc(id)}</p><p>Current status: ${esc(refundLabel(o?.refund) || o?.status || "See checkout history")}. Support replies appear under My support requests.</p>${orderCancellationButton(o || {})}${o?.status === "CONFIRMED" && !o.refund && o.event?.status === "PUBLISHED" ? `<button class="secondary-button" data-resend-order="${esc(id)}">Resend confirmation email</button>` : ""}<form id="supportForm" data-order="${esc(id)}"><label>What do you need help with?<select name="category"><option value="PAYMENT">Payment</option><option value="REFUND">Refund</option><option value="ENTRY">Ticket / entry</option><option value="EVENT">Event information</option><option value="OTHER">Other</option></select></label><label>Details<textarea name="message" required maxlength="3000" placeholder="Describe the issue. Do not include card details, passwords or QR codes."></textarea></label><button class="primary-button" type="submit">Send request</button></form>`,
    );
  }
  const resend = event.target.closest("[data-resend-order]");
  if (resend) {
    resend.disabled = true;
    try {
      await api(
        `/api/account/orders/${encodeURIComponent(resend.dataset.resendOrder)}/resend`,
        { method: "POST" },
      );
      if (document.getElementById("customerToolStatus"))
        document.getElementById("customerToolStatus").textContent =
          "Confirmation queued for your account email. Delivery may take a few minutes.";
    } catch (error) {
      if (document.getElementById("customerToolStatus"))
        document.getElementById("customerToolStatus").textContent =
          error.message;
    } finally {
      resend.disabled = false;
    }
  }
});
document.addEventListener("submit", async (event) => {
  if (event.target.id !== "supportForm") return;
  event.preventDefault();
  const form = event.target,
    user = state.user?.id,
    button = form.querySelector("button");
  button.disabled = true;
  try {
    const data = new FormData(form);
    const result = await api("/api/account/support", {
      method: "POST",
      body: JSON.stringify({
        orderId: form.dataset.order,
        category: data.get("category"),
        message: data.get("message"),
      }),
    });
    if (state.user?.id !== user || !form.isConnected) return;
    form.remove();
    document.getElementById("customerToolStatus").textContent =
      `Request received: ${result.id}. Check My support requests for a reply.`;
  } catch (error) {
    if (form.isConnected)
      document.getElementById("customerToolStatus").textContent = error.message;
  } finally {
    button.disabled = false;
  }
});
async function loadTicketTransfers() {
  const user = state.user?.id,
    box = document.getElementById("ticketTransfers");
  if (!user || !box) return;
  try {
    const [transfers, received] = await Promise.all([
      api("/api/account/transfers"),
      api("/api/account/received-tickets"),
    ]);
    if (state.user?.id !== user || !box.isConnected) return;
    box.innerHTML = `${received.length ? `<h2>Tickets received</h2>${received.map(({ ticket: t, event: e }) => `<article class="ticket-card" data-ticket-bucket="${ticketBucket({ event: e, status: t.status })}">${ticketPoster(e)}<div class="ticket-body"><b class="status-badge">${esc(t.status)}</b><h2>${esc(e.name)}</h2><p>${esc(t.label)} · ${esc(when(e.startInstant, e.timezone))}</p><button class="secondary-button" data-ticket-id="${esc(t.id)}">View ticket</button><div class="ticket-actions">${venueLink(e)} ${calendarLink(e)}</div>${removeTicketButton(e)}<p>The original purchaser retains payment and refund ownership.</p></div></article>`).join("")}` : ""}${transfers.length ? `<h2>Ticket transfers</h2>${transfers.map((t) => `<article class="support-message"><strong>${esc(t.event_name)} · ${esc(t.label)}</strong><p>${t.incoming ? "Incoming" : "Outgoing"} transfer · ${esc(t.status)}</p>${t.status === "PENDING" ? (t.incoming ? `<label>Guest age ${t.minimum_age ? `(minimum ${Number(t.minimum_age)})` : "(optional)"}<input type="number" min="0" max="120" id="transfer-age-${esc(t.id)}"></label><p>Accepting moves entry rights to you. The original purchaser retains refund rights. Bring any required age ID.</p><button class="secondary-button" data-transfer-resolve="${esc(t.id)}" data-transfer-action="ACCEPT">Accept ticket</button> <button class="secondary-button" data-transfer-resolve="${esc(t.id)}" data-transfer-action="DECLINE">Decline</button>` : `<button class="secondary-button" data-transfer-resolve="${esc(t.id)}" data-transfer-action="CANCEL">Cancel transfer</button>`) : ""}</article>`).join("")}` : ""}`;
    state.ticketEvents.push(...received.map((t) => t.event));
    const active = document.querySelector(
      '[data-ticket-filter][aria-pressed="true"]',
    );
    if (active) active.click();
  } catch {
    if (box.isConnected)
      box.textContent =
        "Received tickets and transfers could not be loaded. Refresh to retry.";
  }
}
function orderCancellationButton(o) {
  return o.status === "CONFIRMED" &&
    !o.refund &&
    o.event?.status === "PUBLISHED" &&
    !o.tickets?.some((t) =>
      ["USED", "TRANSFERRED", "INACTIVE"].includes(t.status),
    ) &&
    Date.parse(o.event.startInstant) > Date.now() &&
    o.cancellationDeadline &&
    Date.parse(o.cancellationDeadline) > Date.now()
    ? `<button class="secondary-button" data-cancel-booking="${esc(o.id)}">Cancel booking</button><p>Full cancellation deadline: ${esc(when(o.cancellationDeadline, o.event?.timezone))} · ${esc(hour(o.cancellationDeadline, o.event?.timezone))} (${esc(o.event?.timezone || "Asia/Kolkata")})</p>`
    : "";
}
document.addEventListener("click", async (event) => {
  const transfer = event.target.closest("[data-offer-transfer]");
  if (transfer) {
    customerDialog(
      "Transfer this admission",
      `<p>Transfer entry rights to another registered TickIt account. They must accept in My tickets before the offer expires (24 hours or event start, whichever is earlier). Your existing QR becomes invalid when they accept.</p><p>The original purchaser keeps financial/refund ownership. Self-service cancellation is unavailable while someone else owns any ticket in the order.</p><form id="ticketTransferForm" data-ticket="${esc(transfer.dataset.offerTransfer)}"><label>Recipient’s verified TickIt email<input type="email" name="email" required maxlength="255" autocomplete="off"></label><button class="primary-button" type="submit">Offer transfer</button></form>`,
    );
  }
  const resolve = event.target.closest("[data-transfer-resolve]");
  if (resolve) {
    resolve.disabled = true;
    try {
      const input = document.getElementById(
        `transfer-age-${resolve.dataset.transferResolve}`,
      );
      await api(
        `/api/account/transfers/${encodeURIComponent(resolve.dataset.transferResolve)}`,
        {
          method: "POST",
          body: JSON.stringify({
            action: resolve.dataset.transferAction,
            age: input?.value ? Number(input.value) : null,
          }),
        },
      );
      notify("Transfer updated.");
      route();
    } catch (error) {
      notify(error.message, true);
    } finally {
      resolve.disabled = false;
    }
  }
  const cancel = event.target.closest("[data-cancel-booking]");
  if (cancel) {
    customerDialog(
      "Cancel this booking?",
      `<p>This cancels the entire order and immediately invalidates its admission QRs. A full refund is queued; provider or bank processing may take time. Used tickets or tickets held by another account cannot be cancelled here. This action cannot be undone.</p><button class="primary-button" data-confirm-cancellation="${esc(cancel.dataset.cancelBooking)}">Cancel order & request full refund</button>`,
    );
  }
  const confirm = event.target.closest("[data-confirm-cancellation]");
  if (confirm) {
    confirm.disabled = true;
    try {
      await api(
        `/api/account/orders/${encodeURIComponent(confirm.dataset.confirmCancellation)}/cancel`,
        { method: "POST" },
      );
      document.getElementById("customerToolDialog")?.close();
      notify(
        "Booking cancelled. Your full refund is queued; check My tickets for progress.",
      );
      route();
    } catch (error) {
      if (document.getElementById("customerToolStatus"))
        document.getElementById("customerToolStatus").textContent =
          error.message;
      confirm.disabled = false;
    }
  }
});
document.addEventListener("submit", async (event) => {
  if (event.target.id !== "ticketTransferForm") return;
  event.preventDefault();
  const form = event.target,
    user = state.user?.id,
    button = form.querySelector("button");
  button.disabled = true;
  try {
    await api(
      `/api/tickets/${encodeURIComponent(form.dataset.ticket)}/transfer`,
      {
        method: "POST",
        body: JSON.stringify({ email: new FormData(form).get("email") }),
      },
    );
    if (state.user?.id !== user || !form.isConnected) return;
    document.getElementById("customerToolDialog")?.close();
    notify("Transfer offered. Ask the recipient to accept it in My tickets.");
    if (state.view === "tickets") route();
  } catch (error) {
    if (form.isConnected)
      document.getElementById("customerToolStatus").textContent = error.message;
  } finally {
    button.disabled = false;
  }
});
