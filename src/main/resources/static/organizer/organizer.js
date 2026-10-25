"use strict";
const root = document.getElementById("workspace");
const base = window.TICKIT_API_BASE || "";
const state = {
  profile: null,
  events: [],
  draft: null,
  seats: [],
  pools: [],
  tool: "move",
  editorSelection: [],
  zoom: 1,
  busy: false,
  sessionUser: null,
  loadGeneration: 0,
  routeGeneration: 0,
};
const esc = (v) =>
  String(v ?? "").replace(
    /[&<>"']/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        c
      ],
  );
const money = (n) =>
  new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    maximumFractionDigits: 2,
  }).format(n || 0);
let noticeTimer;
function notice(text) {
  const box = document.getElementById("notice");
  box.textContent = text;
  box.classList.add("visible");
  clearTimeout(noticeTimer);
  noticeTimer = setTimeout(() => box.classList.remove("visible"), 6500);
}
async function api(path, options = {}) {
  const token = await TickItAuth.token();
  const response = await fetch(base + path, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: "Bearer " + token } : {}),
      ...(options.headers || {}),
    },
  });
  let data;
  try {
    data = (await response.json()) ?? {};
  } catch {
    if (response.ok)
      throw new Error(
        "The server returned an incomplete response. Please try again.",
      );
    data = {
      error: "The service is temporarily unavailable. Please try again.",
    };
  }
  if (!response.ok) {
    const error = new Error(
      data.error || "The request could not be completed.",
    );
    error.status = response.status;
    throw error;
  }
  return data;
}
function visual() {
  return `<svg viewBox="0 0 500 330" aria-label="Illustration of an editable seating layout"><rect x="165" y="0" width="170" height="32" rx="5" fill="#476852"/><text x="250" y="21" fill="white" text-anchor="middle" font-size="10" letter-spacing="3">YOUR STAGE</text>${Array.from({ length: 7 }, (_, row) => Array.from({ length: 14 }, (_, col) => `<rect x="${40 + col * 30}" y="${65 + row * 34 + Math.abs(col - 6.5) * 2}" width="19" height="21" rx="4" fill="${row < 2 ? "#b6c8ac" : col < 5 ? "#2f6450" : "#91ab89"}"/>`).join("")).join("")}</svg>`;
}
function landing() {
  root.innerHTML = `<section class="landing"><div class="landing-grid"><div><div class="eyebrow">For the people who bring us together</div><h1>Your idea.<br>Your audience.<br><em>Make it happen.</em></h1><p class="intro">Build your event, shape the room, and give every guest a place to be. One workspace, from the first draft to opening night.</p><button class="primary" data-auth="sign-up">Create your organizer workspace <span>↗</span></button><p class="hint">Already hosting with us? <button class="text-button" data-auth="sign-in">Sign in</button></p></div><div class="landing-visual">${visual()}<div class="visual-caption">A space that feels like your event.</div></div></div><div class="feature-row"><article><span>01 / BUILD THE EXPERIENCE</span><h3>The details make the difference.</h3><p>Dates, venue, city and event information. Keep everything together in a private draft.</p></article><article><span>02 / SHAPE THE ROOM</span><h3>Every venue has its own rhythm.</h3><p>Reserved seating, standing admission, or a mix of both. Create a layout that fits.</p></article><article><span>03 / OPEN THE DOORS</span><h3>Publish when you're ready.</h3><p>Preview your event before it appears on the customer website. Your drafts stay private.</p></article></div></section>`;
}
function onboard() {
  root.innerHTML = `<section class="panel onboard"><div class="eyebrow">A place to start</div><h1>Welcome to your<br>next event.</h1><p>Set the name guests should recognize. Your organizer workspace is separate from your customer account.</p><form id="profileForm"><label>Organizer or organization name<input name="displayName" required minlength="2" maxlength="120" placeholder="Your name, collective or organization"></label><button class="primary" type="submit">Create workspace ↗</button></form></section>`;
}
async function loadWorkspace() {
  const generation = ++state.loadGeneration;
  ++state.routeGeneration;
  if (!TickItAuth.user) {
    state.profile = null;
    landing();
    return;
  }
  document.getElementById("account").innerHTML =
    `<span class="hint">${esc(TickItAuth.user.name)}</span><button class="text-button" data-sign-out>Sign out</button>`;
  root.innerHTML = '<div class="loading">Opening your workspace…</div>';
  try {
    const profile = await api("/api/organizer/profile");
    if (generation !== state.loadGeneration) return;
    state.profile = profile;
    state.events = await api("/api/organizer/events");
    if (generation !== state.loadGeneration) return;
    await route();
  } catch (error) {
    if (generation !== state.loadGeneration) return;
    if (error.status === 403) onboard();
    else {
      root.innerHTML = `<section class="panel onboard"><h1>We'll be right back.</h1><p>${esc(error.message)}</p><button data-retry>Try again</button></section>`;
    }
  }
}
function dashboard() {
  state.draft = null;
  root.innerHTML = `<section class="container"><div class="page-head"><div><div class="eyebrow">${esc(state.profile.displayName)} / Workspace</div><h1>Good things are taking shape.</h1><p>Manage your drafts and published events.</p></div><button class="primary" data-new>Create event <span>＋</span></button></div>${state.events.length ? `<div class="cards">${state.events.map((e) => `<article class="event-card">${e.imageUrl ? `<img src="${esc(e.imageUrl)}" alt="" loading="lazy">` : `<div class="placeholder">${esc(e.category.toLowerCase())}</div>`}<div class="card-body"><span class="badge ${e.status === "DRAFT" ? "draft" : ""}">${esc(e.status)}</span><h2>${esc(e.name)}</h2><div class="meta">${esc(e.city)} · ${esc(e.venueName)}<br>${esc(e.startTime.replace("T", " "))} (${esc(e.timezone)})<br>${e.seats.length} assigned seats · ${e.pools.reduce((n, p) => n + p.capacity, 0)} general-admission capacity</div><div class="card-foot"><span class="hint">${esc(e.admissionMode.toLowerCase())}</span><button data-open="${e.id}">${e.status === "DRAFT" ? "Edit draft" : "View event"} ↗</button></div></div></article>`).join("")}</div>` : `<div class="empty"><h2>Your first event starts here.</h2><p>Create a private draft. Nothing appears on the customer website until you publish.</p><button class="primary" data-new>Create your first event ↗</button></div>`}</section>`;
}
function initialDraft() {
  return {
    name: "",
    description: "",
    category: "MUSIC",
    admissionMode: "SEATED",
    venueName: "",
    address: "",
    city: "",
    startTime: "",
    endTime: "",
    timezone: "Asia/Kolkata",
    guestCapacity: 200,
    imageUrl: "",
    stageLabel: "Stage",
    stageX: 400,
    stageY: 20,
    version: null,
    seats: [],
    pools: [],
    salesStart: null,
    salesEnd: null,
    perOrderLimit: 3,
    minimumAge: null,
    status: "DRAFT",
  };
}
function field(
  label,
  name,
  value = "",
  type = "text",
  extra = "",
  full = false,
) {
  return `<label class="${full ? "full" : ""}">${label}<input name="${name}" type="${type}" value="${esc(value)}" ${extra}></label>`;
}
function select(label, name, options, value) {
  return `<label>${label}<select name="${name}">${options.map(([v, t]) => `<option value="${v}" ${v === value ? "selected" : ""}>${t}</option>`).join("")}</select></label>`;
}
function editor(draft) {
  state.zoom = 1;
  state.editorSelection = [];
  state.tool = "move";
  state.draft = structuredClone(draft);
  state.seats = structuredClone(draft.seats || []);
  state.pools = structuredClone(draft.pools || []);
  const published = draft.status === "PUBLISHED";
  const locked = ["CANCELLED", "DELETED"].includes(draft.status);
  root.innerHTML = `<section class="container"><a class="back" href="#">← All events</a><div class="page-head"><div><div class="eyebrow">${draft.id ? "Event workspace" : "Create an event"} / <span class="badge ${locked ? "" : "draft"}">${esc(draft.status || "Private draft")}</span></div><h1>${locked ? esc(draft.name) : draft.id ? "Keep shaping your event." : "Something worth showing up for."}</h1><p>${locked ? "This event is no longer on sale. Booking and payment records are retained." : published ? "Update available seats or change event dates. Booked seats cannot be removed or relabelled." : "Your draft stays private until you publish it."}</p></div><div class="top-actions"><button type="button" data-preview>Preview event</button>${draft.id ? `<a class="primary" href="#operations/${draft.id}">Bookings & entry</a><button data-duplicate>Duplicate as draft</button>${published ? `<a class="primary" style="padding:12px 18px;border-radius:5px;font-size:12px" href="${esc(base)}${draft.slug ? `/events/${encodeURIComponent(draft.slug)}` : `/#event/${draft.id}`}" target="_blank" rel="noopener">View customer page ↗</a>` : ""}` : ""}</div></div><form id="eventForm"><div class="editor-layout"><div><fieldset ${locked ? "disabled" : ""}><section class="panel"><div class="panel-head"><div><h2><small>01</small> The essentials</h2><p>Tell guests what they can look forward to.</p></div></div><div class="fields">${field("Event name", "name", draft.name, "text", 'required maxlength="512"', true)}<label class="full">Event description<textarea name="description" maxlength="10000" placeholder="The experience, the lineup, and what guests should know…">${esc(draft.description)}</textarea></label>${informationFields(draft.information)}<label class="full"><span><input type="checkbox" name="cancellationEnabled" ${draft.cancellationPolicy?.enabled ? "checked" : ""}> Allow full customer cancellations before the cutoff</span></label>${field("Cancellation cutoff (hours before start)", "cancellationCutoffHours", draft.cancellationPolicy?.cutoffHours || 24, "number", 'min="1" max="720"')}<p class="full">Applies to new purchases. Existing orders keep the cancellation deadline promised at checkout. Full order refunds only; provider processing can take time.</p>${select(
    "Event type",
    "category",
    [
      ["MUSIC", "Music"],
      ["SPORTS", "Sport"],
      ["COMEDY", "Comedy"],
      ["THEATRE", "Theatre"],
      ["CONFERENCE", "Conference"],
      ["COMMUNITY", "Community"],
      ["OTHER", "Other"],
    ],
    draft.category,
  )}${select(
    "Admission format",
    "admissionMode",
    [
      ["SEATED", "Assigned seating"],
      ["GENERAL", "General admission / standing"],
      ["MIXED", "Seated + general admission"],
    ],
    draft.admissionMode,
  )}${field("Start date & time", "startTime", draft.startTime, "datetime-local", "required")}${field("End date & time", "endTime", draft.endTime || "", "datetime-local")}${select(
    "Event timezone",
    "timezone",
    [
      ["Asia/Kolkata", "India — Asia/Kolkata"],
      ["UTC", "UTC"],
      ["Asia/Dubai", "Dubai — Asia/Dubai"],
      ["Europe/London", "London — Europe/London"],
      ["America/New_York", "New York — America/New_York"],
    ],
    draft.timezone,
  )}${field("Maximum guest capacity", "guestCapacity", draft.guestCapacity, "number", 'required min="1" max="100000"')}${field("Sales open (optional)", "salesStart", draft.salesStart || "", "datetime-local")}${field("Sales close (optional)", "salesEnd", draft.salesEnd || "", "datetime-local")}${field("Tickets per account / order", "perOrderLimit", Math.min(3, draft.perOrderLimit || 3), "number", 'min="1" max="3" required')}${field("Minimum guest age (optional)", "minimumAge", draft.minimumAge ?? "", "number", 'min="1" max="120" placeholder="All ages"')}<p class="hint full">Leave blank for all ages. Age eligibility is self-declared; check guest ID at entry when required.</p>${field("Cover image URL", "imageUrl", draft.imageUrl || "", "url", 'placeholder="https://…"', true)}</div>${published ? '<label class="full">Reason for date/time change<textarea name="changeReason" maxlength="1000" placeholder="Explain the change for existing ticket holders"></textarea></label>' : ""}</section><section class="panel"><div class="panel-head"><div><h2><small>02</small> The place</h2><p>Use the real venue and address guests will travel to.</p></div></div><div class="fields">${field("Venue name", "venueName", draft.venueName, "text", 'required maxlength="255"')}${field("City", "city", draft.city, "text", 'required maxlength="100"')}${field("Venue address", "address", draft.address, "text", 'required maxlength="512"', true)}</div></section><section class="panel" id="seatingSection"><div class="panel-head"><div><h2><small>03</small> Shape the room</h2><p>Build a custom seat map. Each seat has its own section, label, position and INR price.</p></div></div><div class="info-strip">Templates are starting layouts. Verify the final map against your actual venue. Use Select seats or Select all to choose any number of seats, then Move seats to drag them together or apply a shared price. Click a selected seat to deselect. Use Remove to exclude unavailable positions. Maximum 1,500 assigned seats.</div><div class="section-builder"><div class="fields"><label>Section name<input id="sectionName" value="Main" maxlength="40"></label><label>Layout<select id="layoutTemplate"><option value="straight">Straight rows</option><option value="fan">Fan-shaped rows</option><option value="split">Two blocks + centre aisle</option></select></label><label>Rows<input id="rowCount" type="number" min="1" max="18" value="6"></label><label>Seats per row<input id="columnCount" type="number" min="1" max="28" value="12"></label><label>Price / seat (INR)<input id="sectionPrice" type="number" min="0" max="999999.99" step="0.01" value="500"></label><label>Left offset<input id="offsetX" type="number" min="20" max="900" value="80"></label><label>Top offset<input id="offsetY" type="number" min="80" max="650" value="130"></label><label>Row prefix<input id="rowPrefix" maxlength="5" placeholder="Optional"></label></div><div class="actions" style="padding:20px 0 0"><button type="button" data-add-section>Add section to map ＋</button><button type="button" data-clear-map>Clear map</button></div></div><div class="controls">${field("Stage label", "stageLabel", draft.stageLabel, "text", 'maxlength="80"')}${field("Stage left", "stageX", draft.stageX, "number", 'min="0" max="800"')}${field("Stage top", "stageY", draft.stageY, "number", 'min="0" max="650"')}</div><div class="map-shell"><div class="map-tools"><button type="button" data-tool="select">Select seats</button><button type="button" class="active" data-tool="move">Move seats</button><button type="button" data-tool="remove">Remove seats</button><button type="button" data-zoom="in" aria-label="Zoom in on seating layout">＋ Zoom</button><button type="button" data-zoom="out" aria-label="Zoom out of seating layout">− Zoom</button><button type="button" data-export>Export CSV</button><button type="button" data-import>Import CSV</button><input id="seatCsv" class="hidden" type="file" accept=".csv,text/csv"><span class="count" id="seatCount"></span></div><div class="controls"><label>Selected seat price (INR)<input id="selectedPrice" type="number" min="0" max="999999.99" step="0.01"></label><button type="button" data-price-selected>Apply price to selected</button><button type="button" data-remove-selected>Remove selected</button><button type="button" data-select-all>Select all seats</button><button type="button" data-clear-selected>Clear selection</button><span id="selectionCount" aria-live="polite"></span></div><div id="layoutMap"></div></div><p class="hint">CSV columns: section,row,number,x,y,price. Coordinates use a 1000 × 700 canvas. Imports replace the current map. Stage position is saved separately.</p></section><section class="panel" id="admissionSection"><div class="panel-head"><div><h2><small>04</small> Admission zones & tickets</h2><p>Each zone has one shared guest capacity. Ticket types within it draw from that same capacity.</p></div></div><div class="info-strip">Example: a 400-person floor can have Early Bird and Regular tickets. Selling either uses the same 400 spaces. A ticket quota limits that price tier; it does not add venue capacity.</div><div id="poolList"></div><button type="button" data-add-pool>Add admission zone ＋</button></section></fieldset><div class="actions">${!locked ? `<button class="primary" type="submit">${published ? "Save changes" : "Save draft"}</button>${!published ? '<button type="button" data-publish>Publish event ↗</button>' : ""}` : ""}${published ? '<button type="button" data-cancel-event>Cancel event</button>' : ""}${draft.id ? '<button type="button" data-delete-event>Delete event</button>' : ""}</div><p class="hint">Published changes update the customer website. Date changes require a reason and expire pending checkouts. Cancellation or deletion preserves booking/payment records; full refunds are queued automatically.</p></div><aside class="side-note"><div class="eyebrow">Your event, at a glance</div><h3>Build with confidence.</h3><p>Assigned seats and standing zones are counted together against your maximum guest capacity.</p><div class="metric" id="inventoryTotal">0</div><small>places configured</small><p id="inventoryStatus"></p><p>Prices for new organizer events are in INR. Drafts are visible only inside your workspace.</p><hr style="border:0;border-top:1px solid #cdd8c8;margin:22px 0"><p>Customer booking limits remain three tickets per account or your lower event limit. Organizer seat selection has no three-seat cap.</p></aside></div></form><dialog id="preview" class="preview-dialog"></dialog></section>`;
  renderPools();
  formatSections();
  renderMap();
  updateInventory();
}
function formatSections() {
  const mode = document.querySelector("[name=admissionMode]")?.value;
  document
    .getElementById("seatingSection")
    ?.classList.toggle("hidden", mode === "GENERAL");
  document
    .getElementById("admissionSection")
    ?.classList.toggle("hidden", mode === "SEATED");
  for (const id of ["seatingSection", "admissionSection"]) {
    const section = document.getElementById(id);
    section
      ?.querySelectorAll("input,select,textarea,button")
      .forEach(
        (input) =>
          (input.disabled =
            section.classList.contains("hidden") ||
            ["CANCELLED", "DELETED"].includes(state.draft?.status)),
      );
  }
  updateInventory();
}
function readPayload() {
  const form = new FormData(document.getElementById("eventForm"));
  const get = (k) => form.get(k) || "";
  // Cancelled event fields remain available for preview and duplication.
  if (["CANCELLED", "DELETED"].includes(state.draft.status))
    return structuredClone(state.draft);
  return {
    salesStart: get("salesStart") || null,
    salesEnd: get("salesEnd") || null,
    perOrderLimit: Number(get("perOrderLimit")),
    minimumAge: get("minimumAge") ? Number(get("minimumAge")) : null,
    name: get("name"),
    description: get("description"),
    cancellationPolicy: {
      enabled: form.has("cancellationEnabled"),
      cutoffHours: Number(get("cancellationCutoffHours")) || null,
    },
    information: Object.fromEntries(
      [
        "languages",
        "artists",
        "entryInstructions",
        "accessibilityInfo",
        "ticketInclusions",
        "faq",
      ].map((k) => [k, get(k)]),
    ),
    category: get("category"),
    admissionMode: get("admissionMode"),
    venueName: get("venueName"),
    address: get("address"),
    city: get("city"),
    startTime: get("startTime"),
    endTime: get("endTime") || null,
    timezone: get("timezone"),
    guestCapacity: Number(get("guestCapacity")),
    imageUrl: get("imageUrl"),
    stageLabel: get("stageLabel"),
    stageX: Number(get("stageX")),
    stageY: Number(get("stageY")),
    version: state.draft.version,
    changeReason: get("changeReason"),
    seats: get("admissionMode") === "GENERAL" ? [] : state.seats,
    pools: get("admissionMode") === "SEATED" ? [] : state.pools,
  };
}
function updateInventory() {
  const total = document.getElementById("inventoryTotal");
  if (!total) return;
  const mode = document.querySelector("[name=admissionMode]").value;
  const count =
    (mode === "GENERAL" ? 0 : state.seats.length) +
    (mode === "SEATED"
      ? 0
      : state.pools.reduce((n, p) => n + Number(p.capacity || 0), 0));
  total.textContent = count.toLocaleString();
  const cap = Number(document.querySelector("[name=guestCapacity]").value);
  const status = document.getElementById("inventoryStatus");
  status.classList.toggle("error", count > cap);
  status.textContent =
    count > cap
      ? `Exceeds your ${cap}-guest limit. Reduce inventory or correct the venue capacity.`
      : `${Math.max(0, cap - count)} places remain within your guest limit.`;
}
function mapMarkup() {
  const stageX = Number(
      document.querySelector("[name=stageX]")?.value ?? state.draft.stageX,
    ),
    stageY = Number(
      document.querySelector("[name=stageY]")?.value ?? state.draft.stageY,
    ),
    stageLabel =
      document.querySelector("[name=stageLabel]")?.value ||
      state.draft.stageLabel;
  const sections = [...new Set(state.seats.map((s) => s.section))];
  return `<svg id="seatCanvas" style="width:${state.zoom * 100}%;max-width:none" viewBox="0 0 1000 700" role="img" aria-label="Custom event seating layout"><rect width="1000" height="700" fill="#fafbf8"/><g data-stage="true" style="cursor:grab"><rect x="${stageX}" y="${stageY}" width="200" height="38" rx="4" fill="#d5dfcf"/><text x="${stageX + 100}" y="${stageY + 24}" text-anchor="middle" font-size="13" fill="#36573c">${esc(stageLabel)}</text></g>${sections
    .map((section, i) => {
      const seats = state.seats.filter((s) => s.section === section);
      return `<text x="${Math.min(...seats.map((s) => s.x))}" y="${Math.max(75, Math.min(...seats.map((s) => s.y)) - 20)}" font-size="12" fill="#697a68">${esc(section)}</text>`;
    })
    .join(
      "",
    )}${state.seats.map((s, i) => `<g data-map-index="${i}" class="map-seat" role="button" tabindex="0" aria-pressed="${state.editorSelection.includes(i)}" aria-label="${esc(s.section)} ${esc(s.row)}${s.number}"><rect x="${s.x}" y="${s.y}" width="22" height="22" rx="4" stroke="${state.editorSelection.includes(i) ? "#122f24" : "none"}" stroke-width="4" fill="${["#8da886", "#45765b", "#afb99a", "#6b8e8a"][sections.indexOf(s.section) % 4]}"/><text x="${s.x + 11}" y="${s.y + 15}" text-anchor="middle" font-size="9" fill="#fff">${s.number}</text><title>${esc(s.section)} / ${esc(s.row)}${s.number} · ${esc(money(s.price))}</title></g>`).join("")}</svg>`;
}
function renderMap() {
  const map = document.getElementById("layoutMap");
  if (!map) return;
  state.editorSelection = state.editorSelection.filter(
    (i) => i < state.seats.length,
  );
  map.innerHTML = mapMarkup();
  const selectedCount = document.getElementById("selectionCount");
  if (selectedCount)
    selectedCount.textContent = `${state.editorSelection.length} selected`;
  document.getElementById("seatCount").textContent =
    state.seats.length + " assigned seats";
  updateInventory();
}
function addSection() {
  const val = (id) => document.getElementById(id).value;
  const name = val("sectionName").trim(),
    rows = Number(val("rowCount")),
    cols = Number(val("columnCount")),
    price = Number(val("sectionPrice")),
    x = Number(val("offsetX")),
    y = Number(val("offsetY")),
    prefix = val("rowPrefix").trim(),
    type = val("layoutTemplate");
  if (
    !name ||
    !Number.isInteger(rows) ||
    rows < 1 ||
    rows > 18 ||
    !Number.isInteger(cols) ||
    cols < 1 ||
    cols > 28 ||
    !Number.isFinite(price) ||
    price < 0 ||
    price > 999999.99 ||
    Math.abs(price * 100 - Math.round(price * 100)) > 0.00001 ||
    !Number.isInteger(x) ||
    !Number.isInteger(y)
  ) {
    notice("Choose a section name, valid row/seat counts and price.");
    return;
  }
  if (state.seats.some((s) => s.section === name)) {
    notice(
      "Use a different section name, or clear that section before rebuilding.",
    );
    return;
  }
  const generated = [];
  for (let row = 0; row < rows; row++)
    for (let col = 0; col < cols; col++) {
      const curve =
        type === "fan"
          ? Math.round(Math.abs(col - (cols - 1) / 2) ** 2 * 0.65)
          : 0;
      const sx =
          x +
          col * 28 +
          (type === "split" && col >= Math.ceil(cols / 2) ? 40 : 0),
        sy = y + row * 32 + curve;
      if (sx < 0 || sx > 970 || sy < 0 || sy > 670) {
        notice(
          "This section extends outside the canvas. Reduce rows/seats or change its offsets.",
        );
        return;
      }
      generated.push({
        section: name,
        row: prefix + String.fromCharCode(65 + row),
        number: col + 1,
        x: sx,
        y: sy,
        price,
      });
    }
  if (state.seats.length + generated.length > 1500) {
    notice("The assigned-seat limit is 1,500 per event.");
    return;
  }
  if (
    generated.some((s) =>
      state.seats.some(
        (other) => Math.abs(other.x - s.x) < 22 && Math.abs(other.y - s.y) < 22,
      ),
    )
  ) {
    notice(
      "This section overlaps existing seat positions. Change its offsets.",
    );
    return;
  }
  state.seats.push(...generated);
  renderMap();
  notice(`${name} added: ${generated.length} seats.`);
}
function renderPools() {
  const list = document.getElementById("poolList");
  if (!list) return;
  list.innerHTML = state.pools
    .map(
      (p, i) =>
        `<div class="zone"><div class="zone-head"><label>Admission zone<input data-pool="${i}" data-field="name" value="${esc(p.name)}" required maxlength="80"></label><label>Shared capacity<input data-pool="${i}" data-field="capacity" type="number" min="1" max="100000" value="${p.capacity}" required></label><button type="button" data-remove-pool="${i}">Remove zone</button></div>${p.tickets.map((t, j) => `<div class="ticket-row"><label>Ticket type<input data-pool="${i}" data-tier="${j}" data-field="name" value="${esc(t.name)}" required maxlength="80"></label><label>Price (INR)<input data-pool="${i}" data-tier="${j}" data-field="price" type="number" value="${t.price}" min="0" max="999999.99" step="0.01" required></label><label>Tier quota<input data-pool="${i}" data-tier="${j}" data-field="quota" type="number" value="${t.quota}" min="1" max="100000" required></label><button type="button" data-remove-tier="${i}:${j}" aria-label="Remove ticket type">×</button></div>`).join("")}<button type="button" data-add-tier="${i}">Add ticket type ＋</button></div>`,
    )
    .join("");
  updateInventory();
}
function preview() {
  const d = readPayload();
  const dialog = document.getElementById("preview");
  dialog.innerHTML = `<button type="button" data-close-preview>Close ×</button><div class="eyebrow">Customer preview / not a publication</div>${d.imageUrl ? `<img src="${esc(d.imageUrl)}" alt="Event cover">` : ""}<h2>${esc(d.name || "Your event title")}</h2><p>${esc(d.city)} · ${esc(d.venueName)}<br>${esc(String(d.startTime).replace("T", " "))} · ${esc(d.timezone)}</p><p class="description">${esc(d.description || "Your event description will appear here.")}</p>${Object.entries(
    d.information || {},
  )
    .filter(([, v]) => v)
    .map(
      ([k, v]) =>
        `<p class="description"><strong>${esc({ languages: "Languages", artists: "Lineup", ticketInclusions: "Ticket inclusions", entryInstructions: "Entry instructions", accessibilityInfo: "Accessibility", faq: "FAQs" }[k] || k)}</strong><br>${esc(v)}</p>`,
    )
    .join(
      "",
    )}<p>${d.cancellationPolicy?.enabled ? `Full cancellation until ${Number(d.cancellationPolicy.cutoffHours)} hours before start (new purchases).` : "Customer cancellation is not offered."}</p>${d.admissionMode !== "GENERAL" ? mapMarkup() : ""}${d.admissionMode !== "SEATED" ? d.pools.map((p) => `<h3>${esc(p.name)} · ${p.capacity} capacity</h3>${p.tickets.map((t) => `<p>${esc(t.name)} · ${money(t.price)}</p>`).join("")}`).join("") : ""}<p>Published events appear on the customer website. Checkout availability depends on configured payment services.</p>`;
  dialog.showModal();
}
async function saveDraft(render = true) {
  const form = document.getElementById("eventForm");
  if (!form.reportValidity()) return null;
  const payload = readPayload();
  const generation = state.routeGeneration;
  const draftId = state.draft.id;
  const d = await api(
    "/api/organizer/events" + (state.draft.id ? "/" + state.draft.id : ""),
    { method: state.draft.id ? "PUT" : "POST", body: JSON.stringify(payload) },
  );
  state.events = state.events.filter((e) => e.id !== d.id);
  state.events.unshift(d);
  if (generation !== state.routeGeneration || state.draft?.id !== draftId)
    return null;
  state.draft = d;
  history.replaceState(null, "", "#event/" + d.id);
  if (render) editor(d);
  return d;
}
async function route() {
  const generation = ++state.routeGeneration;
  if (!state.profile) return;
  const operationsMatch = location.hash.match(/^#operations\/(\d+)$/);
  if (operationsMatch) {
    await window.TickItOperations.render(Number(operationsMatch[1]));
    return;
  }
  if (location.hash === "#new") {
    editor(initialDraft());
    return;
  }
  const match = location.hash.match(/^#event\/(\d+)$/);
  if (match) {
    try {
      const draft = await api("/api/organizer/events/" + match[1]);
      if (generation !== state.routeGeneration) return;
      editor(draft);
    } catch (error) {
      if (generation !== state.routeGeneration) return;
      notice(error.message);
      dashboard();
    }
  } else dashboard();
}
function parseCsv(text) {
  const rows = [];
  let row = [],
    cell = "",
    quote = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (c === '"') {
      if (quote && text[i + 1] === '"') {
        cell += '"';
        i++;
      } else quote = !quote;
    } else if (c === "," && !quote) {
      row.push(cell);
      cell = "";
    } else if ((c === "\n" || c === "\r") && !quote) {
      if (c === "\r" && text[i + 1] === "\n") i++;
      row.push(cell);
      if (row.some((v) => v.trim())) rows.push(row);
      row = [];
      cell = "";
    } else cell += c;
  }
  if (quote) throw new Error("CSV contains an unclosed quoted field.");
  row.push(cell);
  if (row.some((v) => v.trim())) rows.push(row);
  if (rows.length < 2 || rows.length > 1501)
    throw new Error("CSV needs a header and 1–1,500 seats.");
  if (
    rows[0].map((v) => v.trim().replace(/^\uFEFF/, "")).join(",") !==
    "section,row,number,x,y,price"
  )
    throw new Error("CSV header must be section,row,number,x,y,price.");
  const seen = new Set(),
    positions = new Set();
  const parsed = rows.slice(1).map((v, i) => {
    if (v.length !== 6) throw new Error(`CSV row ${i + 2} needs 6 columns.`);
    const [section, row, number, x, y, price] = [
      v[0].trim(),
      v[1].trim(),
      ...v.slice(2).map(Number),
    ];
    if (
      !section ||
      section.length > 40 ||
      !row ||
      row.length > 10 ||
      !Number.isInteger(number) ||
      number < 1 ||
      number > 999 ||
      !Number.isInteger(x) ||
      x < 0 ||
      x > 970 ||
      !Number.isInteger(y) ||
      y < 0 ||
      y > 670 ||
      !Number.isFinite(price) ||
      price < 0 ||
      price > 999999.99 ||
      (Math.round(price * 100) !== price * 100 &&
        Math.abs(Math.round(price * 100) - price * 100) > 0.00001)
    )
      throw new Error(`Invalid values in CSV row ${i + 2}.`);
    const key = section + "/" + row + "/" + number,
      pos = x + ":" + y;
    if (seen.has(key) || positions.has(pos))
      throw new Error(`Duplicate label or position in CSV row ${i + 2}.`);
    seen.add(key);
    positions.add(pos);
    return { section, row, number, x, y, price };
  });
  for (let i = 0; i < parsed.length; i++)
    for (let j = 0; j < i; j++)
      if (
        Math.abs(parsed[i].x - parsed[j].x) < 22 &&
        Math.abs(parsed[i].y - parsed[j].y) < 22
      )
        throw new Error(
          "Imported seats overlap. Leave room for each 22 × 22 seat.",
        );
  return parsed;
}
function toggleEditorSeat(index) {
  const at = state.editorSelection.indexOf(index);
  if (at >= 0) state.editorSelection.splice(at, 1);
  else state.editorSelection.push(index);
  renderMap();
}
document.addEventListener("keydown", (event) => {
  const seat = event.target.closest("[data-map-index]");
  if (
    event.target.closest("#preview") ||
    state.busy ||
    ["CANCELLED", "DELETED"].includes(state.draft?.status)
  )
    return;
  if (seat && (event.key === "Enter" || event.key === " ")) {
    event.preventDefault();
    toggleEditorSeat(Number(seat.dataset.mapIndex));
  }
});
let drag = null;
document.addEventListener("pointercancel", () => {
  if (!drag) return;
  drag.originals.forEach((original) =>
    Object.assign(state.seats[original.i], { x: original.x, y: original.y }),
  );
  drag = null;
  renderMap();
});
document.addEventListener("pointerdown", (event) => {
  if (event.target.closest("#preview")) return;
  if (state.busy || ["CANCELLED", "DELETED"].includes(state.draft?.status))
    return;
  const seat = event.target.closest("[data-map-index]"),
    stage = event.target.closest("[data-stage]");
  if (!seat && !stage) return;
  if (seat && state.tool === "select") {
    toggleEditorSeat(Number(seat.dataset.mapIndex));
    return;
  }
  if (["CANCELLED", "DELETED"].includes(state.draft?.status)) return;
  if (seat && state.tool === "remove") {
    if (
      state.draft.bookedSeatIds?.includes(
        state.seats[Number(seat.dataset.mapIndex)].id,
      )
    ) {
      notice("Booked seats cannot be removed.");
      return;
    }
    state.seats.splice(Number(seat.dataset.mapIndex), 1);
    state.editorSelection = [];
    renderMap();
    return;
  }
  const svg = event.target.closest("svg");
  const point = new DOMPoint(event.clientX, event.clientY).matrixTransform(
    svg.getScreenCTM().inverse(),
  );
  const index = seat ? Number(seat.dataset.mapIndex) : null;
  const indexes =
    seat && state.editorSelection.includes(index)
      ? [...state.editorSelection]
      : seat
        ? [index]
        : [];
  drag = {
    start: point,
    originals: indexes.map((i) => ({
      i,
      x: state.seats[i].x,
      y: state.seats[i].y,
    })),
    index: seat ? Number(seat.dataset.mapIndex) : null,
    stage: !!stage,
    svg,
  };
  svg.setPointerCapture(event.pointerId);
});
document.addEventListener("pointermove", (event) => {
  if (!drag) return;
  const pt = new DOMPoint(event.clientX, event.clientY).matrixTransform(
    drag.svg.getScreenCTM().inverse(),
  );
  const x = Math.max(
      0,
      Math.min(drag.stage ? 800 : 970, Math.round(pt.x / 10) * 10),
    ),
    y = Math.max(
      0,
      Math.min(drag.stage ? 650 : 670, Math.round(pt.y / 10) * 10),
    );
  if (drag.stage) {
    document.querySelector("[name=stageX]").value = x;
    document.querySelector("[name=stageY]").value = y;
    const g = drag.svg.querySelector("[data-stage]");
    g.querySelector("rect").setAttribute("x", x);
    g.querySelector("rect").setAttribute("y", y);
    g.querySelector("text").setAttribute("x", x + 100);
    g.querySelector("text").setAttribute("y", y + 24);
  } else {
    const originals = drag.originals;
    const dx = Math.max(
      -Math.min(...originals.map((s) => s.x)),
      Math.min(
        970 - Math.max(...originals.map((s) => s.x)),
        Math.round((pt.x - drag.start.x) / 10) * 10,
      ),
    );
    const dy = Math.max(
      -Math.min(...originals.map((s) => s.y)),
      Math.min(
        670 - Math.max(...originals.map((s) => s.y)),
        Math.round((pt.y - drag.start.y) / 10) * 10,
      ),
    );
    originals.forEach((original) => {
      const x = original.x + dx,
        y = original.y + dy;
      Object.assign(state.seats[original.i], { x, y });
      const g = drag.svg.querySelector(`[data-map-index="${original.i}"]`);
      g.querySelector("rect").setAttribute("x", x);
      g.querySelector("rect").setAttribute("y", y);
      g.querySelector("text").setAttribute("x", x + 11);
      g.querySelector("text").setAttribute("y", y + 15);
    });
  }
});
document.addEventListener("pointerup", () => {
  if (drag) {
    const moved = new Set(drag.originals.map((s) => s.i));
    const collision = drag.originals.some((original) =>
      state.seats.some(
        (other, i) =>
          !moved.has(i) &&
          Math.abs(other.x - state.seats[original.i].x) < 22 &&
          Math.abs(other.y - state.seats[original.i].y) < 22,
      ),
    );
    if (collision) {
      drag.originals.forEach((original) =>
        Object.assign(state.seats[original.i], {
          x: original.x,
          y: original.y,
        }),
      );
      notice(
        "These seats would overlap other seats. Choose an empty area of the map.",
      );
    }
    drag = null;
    renderMap();
  }
});
document.addEventListener("input", (event) => {
  const input = event.target;
  if (input.dataset.pool !== undefined) {
    const p = state.pools[Number(input.dataset.pool)];
    const target =
      input.dataset.tier === undefined
        ? p
        : p.tickets[Number(input.dataset.tier)];
    target[input.dataset.field] =
      input.type === "number" ? Number(input.value) : input.value;
  }
  if (input.name === "admissionMode") formatSections();
  if (["stageLabel", "stageX", "stageY"].includes(input.name)) renderMap();
  updateInventory();
});
document.addEventListener("change", async (event) => {
  if (event.target.id !== "seatCsv") return;
  const file = event.target.files[0];
  if (!file) return;
  try {
    if (file.size > 1024 * 1024)
      throw new Error("CSV must be smaller than 1 MB.");
    const seats = parseCsv(await file.text());
    if (
      state.seats.length &&
      !confirm("Replace the current seat map with this CSV?")
    )
      return;
    state.seats = seats;
    state.editorSelection = [];
    renderMap();
    notice("Seat map imported. Save the draft to persist it.");
  } catch (error) {
    notice(error.message);
  } finally {
    event.target.value = "";
  }
});
document.addEventListener("submit", async (event) => {
  if (!["profileForm", "eventForm"].includes(event.target.id)) return;
  event.preventDefault();
  if (state.busy) return;
  state.busy = true;
  event.target.inert = true;
  try {
    if (event.target.id === "profileForm") {
      await api("/api/organizer/profile", {
        method: "POST",
        body: JSON.stringify({
          displayName: new FormData(event.target).get("displayName"),
        }),
      });
      await loadWorkspace();
    } else {
      const published = state.draft?.status === "PUBLISHED";
      if (await saveDraft())
        notice(
          published
            ? "Event changes saved."
            : "Draft saved. It is still private.",
        );
    }
  } catch (error) {
    notice(error.message);
  } finally {
    state.busy = false;
    event.target.inert = false;
  }
});
document.addEventListener("click", async (event) => {
  const target = event.target.closest("button,a");
  if (!target) return;
  if (target.dataset.auth) {
    TickItAuth.open(target.dataset.auth);
    return;
  }
  if (target.hasAttribute("data-sign-out")) {
    await TickItAuth.signOut();
    location.reload();
    return;
  }
  if (target.hasAttribute("data-retry")) {
    loadWorkspace();
    return;
  }
  if (target.hasAttribute("data-new")) {
    location.hash = "new";
    return;
  }
  if (target.dataset.open) {
    location.hash = "event/" + target.dataset.open;
    return;
  }
  if (target.hasAttribute("data-preview")) {
    preview();
    return;
  }
  if (target.hasAttribute("data-close-preview")) {
    document.getElementById("preview").close();
    return;
  }
  if (state.busy) return;
  if (target.hasAttribute("data-select-all")) {
    state.editorSelection = state.seats.map((s, i) => i);
    renderMap();
    return;
  }
  if (
    target.hasAttribute("data-cancel-event") ||
    target.hasAttribute("data-delete-event")
  ) {
    const deleting = target.hasAttribute("data-delete-event");
    const reason = prompt(
      deleting
        ? "Why are you deleting this event? It will leave the catalog; booking/payment records are retained. Full refunds are queued automatically."
        : "Why is this event cancelled? Ticket holders will see this reason. Full refunds are queued automatically.",
    );
    if (!reason?.trim()) return;
    if (
      !confirm(
        deleting
          ? "Delete this event from your workspace and customer catalog? Captured payments will be refunded automatically; receipts are retained."
          : "Cancel this event and stop ticket sales? Captured payments will be refunded automatically; booking records are retained.",
      )
    )
      return;
    state.busy = true;
    const generation = state.routeGeneration;
    try {
      const result = await api(
        `/api/organizer/events/${state.draft.id}${deleting ? "" : "/cancel"}`,
        {
          method: deleting ? "DELETE" : "POST",
          body: JSON.stringify({
            version: state.draft.version,
            reason: reason.trim(),
          }),
        },
      );
      state.events = state.events.filter((e) => e.id !== result.id);
      if (!deleting) state.events.unshift(result);
      if (generation !== state.routeGeneration) return;
      if (deleting) {
        location.hash = "";
        dashboard();
      } else editor(result);
      notice(
        deleting
          ? "Event deleted. Booking records were preserved."
          : "Event cancelled. Full refunds are queued for captured payments.",
      );
    } catch (error) {
      notice(error.message);
    } finally {
      state.busy = false;
    }
    return;
  }
  if (target.hasAttribute("data-clear-selected")) {
    state.editorSelection = [];
    renderMap();
  }
  if (
    target.hasAttribute("data-price-selected") ||
    target.hasAttribute("data-remove-selected")
  ) {
    if (["CANCELLED", "DELETED"].includes(state.draft?.status)) return;
    if (!state.editorSelection.length) {
      notice("Select seats first using Select seats.");
      return;
    }
    if (target.hasAttribute("data-price-selected")) {
      const input = document.getElementById("selectedPrice");
      if (!input.value || !input.checkValidity()) {
        notice("Enter a valid price first.");
        return;
      }
      state.editorSelection.forEach(
        (i) => (state.seats[i].price = Number(input.value)),
      );
    } else {
      if (
        state.editorSelection.some((i) =>
          state.draft.bookedSeatIds?.includes(state.seats[i].id),
        )
      ) {
        notice(
          "Booked seats cannot be deleted. Cancel the event if it will not take place.",
        );
        return;
      }
      state.seats = state.seats.filter(
        (s, i) => !state.editorSelection.includes(i),
      );
      state.editorSelection = [];
    }
    renderMap();
  }
  if (target.hasAttribute("data-add-section")) addSection();
  if (
    target.hasAttribute("data-clear-map") &&
    confirm("Clear all assigned seats from this draft?")
  ) {
    state.seats = [];
    state.editorSelection = [];
    renderMap();
  }
  if (target.dataset.zoom) {
    state.zoom = Math.max(
      1,
      Math.min(4, state.zoom + (target.dataset.zoom === "in" ? 0.5 : -0.5)),
    );
    renderMap();
  }
  if (target.dataset.tool) {
    state.tool = target.dataset.tool;
    document
      .querySelectorAll("[data-tool]")
      .forEach((b) => b.classList.toggle("active", b === target));
  }
  if (target.hasAttribute("data-add-pool")) {
    if (state.pools.length >= 12) {
      notice("You can configure up to 12 admission zones.");
      return;
    }
    state.pools.push({
      name: "",
      capacity: 100,
      tickets: [{ name: "Standard", price: 500, quota: 100 }],
    });
    renderPools();
  }
  if (target.dataset.removePool !== undefined) {
    state.pools.splice(Number(target.dataset.removePool), 1);
    renderPools();
  }
  if (target.dataset.addTier !== undefined) {
    const p = state.pools[Number(target.dataset.addTier)];
    if (p.tickets.length >= 8) {
      notice("A zone supports up to 8 ticket types.");
      return;
    }
    p.tickets.push({ name: "", price: 500, quota: p.capacity });
    renderPools();
  }
  if (target.dataset.removeTier !== undefined) {
    const [i, j] = target.dataset.removeTier.split(":").map(Number);
    state.pools[i].tickets.splice(j, 1);
    renderPools();
  }
  if (target.hasAttribute("data-export")) {
    const cell = (v) => '"' + String(v).replaceAll('"', '""') + '"';
    const csv =
      "section,row,number,x,y,price\n" +
      state.seats
        .map((s) =>
          [s.section, s.row, s.number, s.x, s.y, s.price].map(cell).join(","),
        )
        .join("\n");
    const url = URL.createObjectURL(new Blob([csv], { type: "text/csv" }));
    const link = document.createElement("a");
    link.href = url;
    link.download = "seat-layout.csv";
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  if (target.hasAttribute("data-import"))
    document.getElementById("seatCsv").click();
  if (target.hasAttribute("data-publish")) {
    if (!document.getElementById("eventForm").reportValidity()) return;
    if (
      !confirm(
        "Publish this event to the customer website? Booked seats will be protected from deletion or relabelling.",
      )
    )
      return;
    state.busy = true;
    const generation = state.routeGeneration;
    const form = document.getElementById("eventForm");
    form.inert = true;
    target.disabled = true;
    try {
      const saved = await saveDraft(false);
      if (saved) {
        const published = await api(
          `/api/organizer/events/${saved.id}/publish`,
          { method: "POST", body: JSON.stringify({ version: saved.version }) },
        );
        state.events = state.events.filter((e) => e.id !== published.id);
        state.events.unshift(published);
        if (generation !== state.routeGeneration) return;
        editor(published);
        notice("Event published. Guests can now discover it.");
      }
    } catch (error) {
      notice(error.message);
    } finally {
      state.busy = false;
      form.inert = false;
      target.disabled = false;
    }
  }
  if (target.hasAttribute("data-duplicate")) {
    state.busy = true;
    const generation = state.routeGeneration;
    try {
      const copy = structuredClone(state.draft);
      delete copy.id;
      delete copy.status;
      delete copy.currency;
      copy.version = null;
      copy.name += " — copy";
      const d = await api("/api/organizer/events", {
        method: "POST",
        body: JSON.stringify(copy),
      });
      state.events.unshift(d);
      if (generation !== state.routeGeneration) return;
      history.replaceState(null, "", "#event/" + d.id);
      editor(d);
      notice("A separate private draft was created.");
    } catch (error) {
      notice(error.message);
    } finally {
      state.busy = false;
    }
  }
});
window.addEventListener("hashchange", () => {
  if (state.profile) route();
});
window.addEventListener("tickit:auth-error", (event) => notice(event.detail));
TickItAuth.onChange((user) => {
  if (user?.id !== state.sessionUser?.id) {
    state.sessionUser = user;
    loadWorkspace();
  }
});
landing();
TickItAuth.init()
  .then(() => {
    if (TickItAuth.user && !state.profile) loadWorkspace();
  })
  .catch((error) => notice(error.message));

function informationFields(info = {}) {
  const fields = [
    ["languages", "Languages", 160],
    ["artists", "Artists / speakers", 1000],
    ["ticketInclusions", "What your ticket includes", 3000],
    ["entryInstructions", "Entry instructions / what to bring", 3000],
    ["accessibilityInfo", "Accessibility information", 1000],
    ["faq", "Frequently asked questions", 4000],
  ];
  return fields
    .map(
      ([name, label, max]) =>
        `<label class="full">${label}<textarea name="${name}" maxlength="${max}" placeholder="Optional — only include confirmed information">${esc(info?.[name])}</textarea></label>`,
    )
    .join("");
}
