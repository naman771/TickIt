"use strict";
const ICONS = {
  arrow: '<path d="M4 10h12m-5-5 5 5-5 5"/>',
  back: '<path d="M16 10H4m5-5-5 5 5 5"/>',
  search: '<circle cx="8.5" cy="8.5" r="5.5"/><path d="m13 13 4 4"/>',
  pin: '<path d="M16 8c0 5-6 9-6 9S4 13 4 8a6 6 0 1 1 12 0Z"/><circle cx="10" cy="8" r="2"/>',
  calendar:
    '<rect x="3" y="4" width="14" height="13" rx="2"/><path d="M6 2v4m8-4v4M3 9h14"/>',
  music:
    '<path d="M8 14V4l9-2v10M8 7l9-2"/><ellipse cx="5" cy="14.5" rx="3" ry="2.5"/><ellipse cx="14" cy="12.5" rx="3" ry="2.5"/>',
  flag: '<path d="M4 18V3c4-3 8 3 12 0v8c-4 3-8-3-12 0"/>',
  ticket:
    '<path d="M2 5h16v4a2 2 0 0 0 0 4v3H2v-3a2 2 0 0 0 0-4V5Z"/><path d="M12 6v2m0 2v2m0 2v1"/>',
  lock: '<rect x="4" y="8" width="12" height="9" rx="2"/><path d="M6 8V6a4 4 0 0 1 8 0v2"/>',
  check: '<path d="m4 10 4 4 8-8"/>',
};
const icon = (name) =>
  `<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.35" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${ICONS[name] || ICONS.arrow}</svg>`;
const esc = (value) =>
  String(value ?? "").replace(
    /[&<>"']/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        c
      ],
  );
const state = {
  events: [],
  seatCache: {},
  seats: [],
  event: null,
  selected: null,
  selectedSeats: [],
  attendeeAge: "",
  user: null,
  hold: null,
  category: "all",
  search: "",
  busy: false,
  view: "events",
  tickets: [],
  connection: false,
};
const app = document.getElementById("app");
const accountControls = document.getElementById("accountControls");
const dialog = document.getElementById("confirmationDialog");
let stomp = null,
  subscription = null,
  routeSequence = 0;
const money = (number, currency = state.event?.currency || "USD") =>
  new Intl.NumberFormat("en-US", {
    style: "currency",
    currency,
    maximumFractionDigits: 2,
    minimumFractionDigits: Number(number) % 1 ? 2 : 0,
  }).format(number || 0);
const day = (date, timezone = "Asia/Kolkata") =>
  new Date(date).toLocaleDateString("en-GB", {
    timeZone: timezone,
    day: "numeric",
    month: "short",
    year: "numeric",
  });
const when = (date, timezone = "Asia/Kolkata") =>
  new Date(date).toLocaleDateString("en-GB", {
    weekday: "short",
    timeZone: timezone,
    day: "numeric",
    month: "long",
  });
const hour = (date, timezone = "Asia/Kolkata") =>
  new Date(date).toLocaleTimeString("en-GB", {
    timeZone: timezone,
    hour: "2-digit",
    minute: "2-digit",
  });
const isRace = (e) => /formula|race|prix/i.test(e.name);
const photo = (e) =>
  e.imageUrl ||
  (isRace(e)
    ? "/images/racing.jpg"
    : /taylor|eras/i.test(e.name)
      ? "/images/eras.jpg"
      : "/images/concert.jpg");
const shortName = (event) => event.name;
const tour = (event) =>
  event.name.split(":").slice(1).join(":").trim() || event.venueName;
const city = (e) => e.city || (e.venueLocation || "").split(",")[0];
const fromPrice = (e) =>
  e.fromPrice ??
  Math.min(
    ...(state.seatCache[e.id] || [])
      .filter((s) => s.status === "FREE")
      .map((s) => s.price),
  );
const categoryName = (e) =>
  ({
    MUSIC: "Live music",
    SPORTS: "Sport",
    COMEDY: "Comedy",
    THEATRE: "Theatre",
    CONFERENCE: "Conference",
    COMMUNITY: "Community",
    OTHER: "Experience",
  })[e.category] || "Experience";
let admissionPools = [],
  admissionSelection = null,
  admissionQuantity = 1;
const selectedSeat = () => state.seats.find((s) => s.seatId === state.selected);
const owns = (seat) =>
  state.hold &&
  state.hold.eventId === state.event?.id &&
  state.hold.seatId === seat?.seatId &&
  state.hold.userId === state.user?.id;

async function api(url, options = {}) {
  const token = await TickItAuth.token();
  const response = await fetch(url, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
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
    const err = new Error(
      response.status === 429
        ? "You’ve made several requests in a short time. Please wait a few seconds and try again."
        : data.error || "We couldn’t complete that request. Please try again.",
    );
    err.status = response.status;
    throw err;
  }
  return data;
}
function notify(message, error = false) {
  const el = document.createElement("div");
  el.className = "notification" + (error ? " error" : "");
  el.textContent = message;
  document.getElementById("notifications").append(el);
  setTimeout(() => el.remove(), 5500);
}
function rememberHold() {
  if (state.hold)
    sessionStorage.setItem("tickit-hold", JSON.stringify(state.hold));
  else sessionStorage.removeItem("tickit-hold");
}
function clearHold() {
  state.hold = null;
  rememberHold();
}
function routeTo(hash) {
  if (location.hash === hash) route();
  else location.hash = hash;
}
function setNav(view) {
  document
    .querySelectorAll("[data-nav]")
    .forEach((el) => el.classList.toggle("active", el.dataset.nav === view));
}

function renderDiscovery() {
  state.view = "events";
  setNav("events");
  app.innerHTML = `<div class="wrap"><section class="hero"><div class="hero-copy"><div class="eyebrow">For the moments that matter</div><h1>Some nights<br><em>stay with you.</em></h1><p>The artists you love. The places you want to be.<br>Find your next live experience, and make it yours.</p><a class="hero-link" href="#discover">Explore what’s on ${icon("arrow")}</a></div><div class="hero-picture"><img src="/images/concert.jpg" alt="A crowd gathered under the lights at a live concert" fetchpriority="high"><span class="photo-credit">LIVE, IN THE MOMENT</span><div class="hero-photo-caption"><span>MORE THAN A TICKET</span><strong>A story you’ll tell for years.</strong></div></div></section><section class="discovery" id="discover"><div class="section-heading"><h2>Worth being there for.</h2><p>${state.events.length} upcoming experiences</p></div><div class="discovery-toolbar"><div class="filters" role="group" aria-label="Event category"><button class="filter ${state.category === "all" ? "active" : ""}" data-category="all">All experiences</button><button class="filter ${state.category === "music" ? "active" : ""}" data-category="music">${icon("music")} Music</button><button class="filter ${state.category === "sports" ? "active" : ""}" data-category="sports">Sport</button><button class="filter" data-category="comedy">Comedy</button><button class="filter" data-category="theatre">Theatre</button><button class="filter" data-category="conference">Conferences</button><button class="filter" data-category="community">Community</button><button class="filter" data-category="other">Other</button></div><label class="search-box">${icon("search")}<input id="eventSearch" type="search" placeholder="Artist, event or city" aria-label="Search events" value="${esc(state.search)}"></label></div><div class="events-grid" id="eventsGrid"></div></section><section class="editorial-note"><div><span class="note-number">LESS FRICTION. MORE ANTICIPATION.</span><h2 class="note-title">Your seat, with a little breathing room.</h2></div><p class="note-copy">Compare available seats and admission options. Your selection summary shows ticket prices and whether checkout is available.</p></section></div>`;
  document
    .querySelectorAll("[data-category]")
    .forEach((button) =>
      button.classList.toggle(
        "active",
        button.dataset.category === state.category,
      ),
    );
  renderEvents();
  document.getElementById("eventSearch").addEventListener("input", (event) => {
    state.search = event.target.value;
    renderEvents();
  });
}
function renderEvents() {
  const grid = document.getElementById("eventsGrid");
  if (!grid) return;
  const matches = state.events.filter(
    (e) =>
      (state.category === "all" ||
        (e.category || "MUSIC").toLowerCase() === state.category) &&
      `${e.name} ${e.venueName} ${e.venueLocation} ${e.city || ""}`
        .toLowerCase()
        .includes(state.search.toLowerCase()),
  );
  grid.innerHTML =
    matches
      .map(
        (e) =>
          `<article class="event-card"><a class="event-photo" href="#event/${e.id}" aria-label="Find tickets for ${esc(e.name)}" style="display:block"><img src="${esc(photo(e))}" alt="${isRace(e) ? "Formula racing cars on a circuit" : "The atmosphere of a live music performance"}" loading="lazy"><span class="event-type">${esc(categoryName(e))}</span><span class="event-date">${icon("calendar")}${esc(day(e.startInstant || e.startTime, e.timezone))}</span></a><div class="event-copy"><div class="event-location">${icon("pin")}${esc(city(e))}</div><h3><a href="#event/${e.id}">${esc(shortName(e))}</a></h3><p class="event-subtitle">${esc(tour(e))} <span aria-hidden="true">·</span> ${esc(e.venueName)}</p><div class="event-card-bottom"><span class="price"><small>From</small>${Number.isFinite(fromPrice(e)) ? money(fromPrice(e), e.currency || "USD") : "—"}</span><a class="text-action" href="#event/${e.id}">Find tickets ${icon("arrow")}</a></div></div></article>`,
      )
      .join("") ||
    '<div class="empty-state"><h2>Nothing here, just yet.</h2><p>Try another artist, city, or category.</p></div>';
}
function renderBooking() {
  const e = state.event;
  state.view = "booking";
  setNav("events");
  app.innerHTML = `<div class="wrap"><div class="page-top"><a class="back-link" href="#events">${icon("back")} Back to experiences</a><div class="flow-steps" aria-label="Booking progress"><span class="active"><b>1</b>Choose a seat</span><span class="${state.hold ? "active" : ""}"><b>2</b>Reserve & confirm</span><span><b>3</b>You’re going</span></div></div><section class="event-masthead"><img src="${esc(photo(e))}" alt=""><div><div class="eyebrow">${esc(categoryName(e))} / ${esc(city(e))}</div><h1>${esc(e.name)}</h1><div class="meta-line"><span>${icon("calendar")}${esc(when(e.startInstant || e.startTime, e.timezone))} · ${esc(hour(e.startInstant || e.startTime, e.timezone))}</span><span>${icon("pin")}${esc(e.venueName)}</span></div></div></section><div class="booking-layout"><section><div class="map-panel"><div class="map-head"><div><h2>Find your place in the crowd.</h2><p>Select up to ${Math.min(3, e.perOrderLimit || 3)} available seats. Click again to deselect.</p></div><span class="live-indicator"><i class="live-dot"></i><span id="availabilityCount"></span></span></div><div class="stage">${isRace(e) ? "TRACK / FINISH STRAIGHT" : "STAGE / FRONT OF HOUSE"}</div><div class="map-scroll" tabindex="0" role="region" aria-label="Seat map, scroll horizontally to see every seat"><div class="seat-rows" id="seatGrid"></div></div><div class="map-legend"><span><i class="swatch"></i>Available</span><span><i class="swatch chosen"></i>Your selection</span><span><i class="swatch reserved"></i>Reserved</span><span><i class="swatch unavailable"></i>Booked</span></div></div><p class="map-caption">Prices shown in ${esc(e.currency || "USD")}. Select a seat to see its price and availability.</p></section><aside class="checkout" id="checkout" aria-label="Reservation summary"></aside></div></div>`;
  const mode = e.admissionMode || "SEATED";
  const mapPanel = document.querySelector(".map-panel");
  if (mode === "GENERAL") {
    mapPanel.innerHTML =
      '<div class="map-head"><div><h2>Your place in the experience.</h2><p>No assigned seats. Choose an admission zone and ticket quantity.</p></div></div><div id="admissionOptions"></div>';
    document.querySelector(".map-caption").textContent =
      "General admission is unassigned. Your ticket grants access to the selected zone.";
    document.querySelector(".flow-steps span").innerHTML =
      "<b>1</b>Choose admission";
  } else if (mode === "MIXED") {
    mapPanel.insertAdjacentHTML(
      "afterend",
      '<section class="map-panel admission-panel"><div class="map-head"><div><h2>Prefer general admission?</h2><p>These zones have no assigned seats.</p></div></div><div id="admissionOptions"></div></section>',
    );
  }
  if (e.minimumAge)
    document
      .querySelector(".event-masthead")
      .insertAdjacentHTML(
        "afterend",
        `<p class="map-caption age-restriction"><strong>Ages ${e.minimumAge}+</strong> · Every guest must meet this age requirement. Bring valid ID if requested at entry.</p>`,
      );
  renderSeatMap();
  renderAdmissionOptions();
  renderCheckout();
  subscribe();
}
function renderSeatMap() {
  const grid = document.getElementById("seatGrid");
  if (!grid) return;
  if (
    state.seats.length &&
    state.seats.every((s) => Number.isInteger(s.x) && Number.isInteger(s.y))
  ) {
    grid.classList.add("custom-layout");
    grid.innerHTML = `<div class="positioned-map" style="width:1000px;height:700px;position:relative"><div class="custom-stage" style="left:${Number(state.event.stageX || 0)}px;top:${Number(state.event.stageY || 0)}px">${esc(state.event.stageLabel || "Stage")}</div>${[
      ...new Set(state.seats.map((s) => s.section)),
    ]
      .map((section) => {
        const group = state.seats.filter((s) => s.section === section);
        return `<span class="map-section-label" style="left:${Math.min(...group.map((s) => s.x))}px;top:${Math.max(60, Math.min(...group.map((s) => s.y)) - 26)}px">${esc(section)}</span>`;
      })
      .join("")}${[...new Set(state.seats.map((s) => s.section + "|" + s.row))]
      .map((key) => {
        const group = state.seats.filter(
          (s) => s.section + "|" + s.row === key,
        );
        return `<span class="map-row-label" style="left:${Math.max(0, Math.min(...group.map((s) => s.x)) - 22)}px;top:${Math.min(...group.map((s) => s.y)) + 5}px">${esc(group[0].row)}</span>`;
      })
      .join(
        "",
      )}${state.seats.map((s) => `<button class="seat ${s.status.toLowerCase()} ${state.selectedSeats.includes(s.seatId) ? "selected" : ""}" data-seat="${s.seatId}" data-status="${s.status}" style="position:absolute;left:${s.x}px;top:${s.y}px" title="${esc(s.label)}" aria-pressed="${state.selectedSeats.includes(s.seatId)}" aria-label="${esc(s.label)}, ${money(s.price, s.currency)}, ${esc(s.status.toLowerCase())}" ${s.status === "BOOKED" || (s.status === "HELD" && !owns(s)) ? "disabled" : ""}>${s.number}</button>`).join("")}</div>`;
    const oldStage = document.querySelector(".map-panel > .stage");
    if (oldStage) oldStage.hidden = true;
    document.getElementById("availabilityCount").textContent =
      `${state.seats.filter((s) => s.status === "FREE").length} seats available`;
    return;
  }
  grid.classList.remove("custom-layout");
  const rows = {};
  state.seats.forEach((s) => (rows[s.row] ??= []).push(s));
  grid.innerHTML = Object.keys(rows)
    .sort()
    .map(
      (row) =>
        `<div class="seat-row"><span class="row-label">${esc(row)}</span><div class="row-seats">${rows[
          row
        ]
          .sort((a, b) => a.number - b.number)
          .map((s) => {
            const owned = owns(s),
              chosen = state.selectedSeats.includes(s.seatId);
            return `<button class="seat ${s.status.toLowerCase()} ${chosen ? "selected" : ""} ${owned ? "owned" : ""}" data-seat="${s.seatId}" data-status="${s.status}" aria-label="Row ${esc(s.row)}, seat ${s.number}, ${money(s.price)}, ${s.status.toLowerCase()}" aria-pressed="${chosen}" ${s.status === "BOOKED" || (s.status === "HELD" && !owned) ? "disabled" : ""}>${s.number}</button>`;
          })
          .join("")}</div><span class="row-label">${esc(row)}</span></div>`,
    )
    .join("");
  document.getElementById("availabilityCount").textContent =
    `${state.seats.filter((s) => s.status === "FREE").length} seats available`;
}
function renderCheckout() {
  const focused = document.activeElement?.id;
  renderCheckoutContents();
  if (["attendeeAge", "admissionQuantity"].includes(focused))
    document.getElementById(focused)?.focus({ preventScroll: true });
}
function renderCheckoutContents() {
  const box = document.getElementById("checkout");
  if (!box) return;
  if (admissionSelection || state.event?.admissionMode === "GENERAL") {
    renderAdmissionCheckout();
    return;
  }
  const seats = state.seats.filter(
    (s) =>
      state.selectedSeats.includes(s.seatId) &&
      (s.status === "FREE" || owns(s)),
  );
  state.selectedSeats = seats.map((s) => s.seatId);
  const total = seats.reduce((n, s) => n + Number(s.price), 0);
  box.innerHTML = `<h2>Your selection</h2><p class="checkout-user">${seats.length} / ${Math.min(3, state.event?.perOrderLimit || 3)} seats selected per account for this event</p>${seats.length ? seats.map((s) => `<div class="selected-summary"><strong>${esc(s.label)}</strong><span>${money(s.price)}</span><button class="inline-button" data-deselect-seat="${s.seatId}" aria-label="Remove ${esc(s.label)}">Remove</button></div>`).join("") : '<div class="checkout-empty">Choose up to three available seats on the map.</div>'}<div class="checkout-line checkout-total"><span>Total (${esc(state.event?.currency || "USD")})</span><strong>${money(total)}</strong></div>${seats.length ? ageDeclaration() : ""}${!state.user && seats.length ? '<button class="primary-button" data-auth="sign-in">Sign in to continue</button>' : '<button class="primary-button" disabled>Checkout unavailable</button>'}<p class="checkout-user">Online checkout is not yet available. Selecting seats does not reserve them or confirm a booking.</p>`;
}
async function refreshSeats() {
  const eventId = state.event?.id;
  if (!eventId || state.view !== "booking") return;
  try {
    const seats = await api(`/api/events/${eventId}/seats`);
    if (state.event?.id !== eventId || state.view !== "booking") return;
    state.seats = seats;
    state.seatCache[eventId] = seats;
    if (state.event.admissionMode && state.event.admissionMode !== "SEATED") {
      const pools = await api(`/api/events/${eventId}/admission`);
      if (state.event?.id !== eventId || state.view !== "booking") return;
      admissionPools = pools;
      renderAdmissionOptions();
    }
    if (selectedSeat()?.status === "BOOKED" && !state.busy) {
      state.selected = null;
      if (state.hold?.eventId === eventId) clearHold();
    }
    state.selectedSeats = state.selectedSeats.filter((id) =>
      seats.some((s) => s.seatId === id && (s.status === "FREE" || owns(s))),
    );
    renderSeatMap();
    renderCheckout();
  } catch {
    /* Keep the current map during a transient outage. */
  }
}
function updateCountdown() {
  const el = document.getElementById("holdCountdown");
  if (!state.hold) return;
  const left = Math.max(
    0,
    Math.ceil((state.hold.deadline - Date.now()) / 1000),
  );
  if (el)
    el.textContent = `${Math.floor(left / 60)}:${String(left % 60).padStart(2, "0")}`;
  if (left === 0 && !state.busy) {
    clearHold();
    notify("Your hold has expired. You can choose an available seat again.");
    refreshSeats();
    renderCheckout();
  }
}
async function acquire() {
  const seat = selectedSeat();
  if (!seat || state.busy) return;
  if (!state.user) {
    TickItAuth.open("sign-in");
    return;
  }
  if (!TickItAuth.config.checkoutEnabled) return;
  state.busy = true;
  renderCheckout();
  try {
    const data = await api("/api/holds", {
      method: "POST",
      body: JSON.stringify({
        eventId: state.event.id,
        seatId: seat.seatId,
      }),
    });
    state.hold = {
      eventId: state.event.id,
      seatId: seat.seatId,
      userId: state.user.id,
      deadline: Date.now() + data.expiresInSeconds * 1000,
    };
    rememberHold();
    seat.status = "HELD";
    renderSeatMap();
  } catch (err) {
    notify(err.message, true);
    await refreshSeats();
  } finally {
    state.busy = false;
    renderCheckout();
  }
}
async function release() {
  if (!state.hold || state.busy) return;
  const h = state.hold;
  state.busy = true;
  renderCheckout();
  try {
    await api(`/api/holds/${h.eventId}/${h.seatId}`, {
      method: "DELETE",
    });
    clearHold();
    notify("Your seat has been released.");
  } catch (err) {
    if (err.status === 409) clearHold();
    notify(err.message, true);
  } finally {
    state.busy = false;
    await refreshSeats();
    renderCheckout();
  }
}
async function book() {
  if (!state.hold || state.busy) return;
  const h = state.hold,
    e = state.event,
    seat = selectedSeat();
  state.busy = true;
  renderCheckout();
  try {
    const result = await api("/api/bookings", {
      method: "POST",
      body: JSON.stringify({
        eventId: h.eventId,
        seatId: h.seatId,
      }),
    });
    clearHold();
    state.selected = null;
    seat.status = "BOOKED";
    showConfirmation(result, e, seat);
  } catch (err) {
    notify(err.message, true);
    if (err.status === 410) clearHold();
  } finally {
    state.busy = false;
    await refreshSeats();
    renderCheckout();
  }
}
function showConfirmation(result, event, seat) {
  dialog.innerHTML = `<div class="confirmation-icon">${icon("check")}</div><div class="eyebrow">Reservation confirmed</div><h2 id="confirmationTitle">You’re going.</h2><p>A seat with your name on it. Your reservation details are saved in My tickets.</p><div class="confirmation-details"><h3>${esc(shortName(event))} · ${esc(tour(event))}</h3><p><span>Date</span><strong>${esc(day(event.startTime))}</strong></p><p><span>Your seat</span><strong>${esc(seat.section || "Main")} · Row ${esc(seat.row)}, Seat ${seat.number}</strong></p><p><span>Reference</span><strong>TK-${String(result.bookingId).padStart(6, "0")}</strong></p></div><button class="primary-button" data-action="view-tickets">View my tickets ${icon("arrow")}</button><button class="quiet-button" data-action="close-confirmation">Continue exploring</button><p style="font-size:9px;text-align:center">Your reservation details are saved in your account.</p>`;
  dialog.showModal();
}
async function renderTickets(sequence) {
  state.view = "tickets";
  setNav("tickets");
  if (!state.user) {
    app.innerHTML = `<div class="wrap"><div class="account-welcome"><div class="eyebrow">Your account</div><h1>Good things ahead.</h1><p>Sign in to see your tickets and keep your next great night close.</p><div class="account-welcome-actions"><button class="primary-button" data-auth="sign-in">Sign in</button><button class="secondary-button" data-auth="sign-up">Create account</button></div></div></div>`;
    return;
  }
  app.innerHTML =
    '<div class="loading-state"><span class="loader"></span>Finding your tickets…</div>';
  try {
    const tickets = await api("/api/bookings/me");
    if (sequence !== routeSequence) return;
    app.innerHTML = `<div class="wrap"><div class="page-heading"><div class="eyebrow">Good things ahead</div><h1>Your next “I was there.”</h1><p>Reservations for ${esc(state.user.name)}. All the details, in one place.</p></div><div class="tickets-list">${
      tickets.length
        ? tickets
            .slice()
            .reverse()
            .map((t) => {
              const e = state.events.find((e) => e.id === t.eventId);
              return e
                ? `<article class="ticket-card"><img src="${esc(photo(e))}" alt=""><div class="ticket-body"><div class="ticket-top"><span>TK-${String(t.bookingId).padStart(6, "0")}</span><b class="status-badge">CONFIRMED</b></div><h2>${esc(shortName(e))} · ${esc(tour(e))}</h2><div class="meta-line"><span>${icon("calendar")}${esc(day(e.startInstant || e.startTime, e.timezone))}</span><span>${icon("pin")}${esc(e.venueName)}</span></div></div><div class="ticket-stub"><span>YOUR SEAT</span><strong>${esc(t.seatLabel)}</strong><small>Reserved admission</small></div></article>`
                : "";
            })
            .join("")
        : `<div class="empty-state"><h2>Something to look forward to.</h2><p>Your confirmed reservations will appear here.</p><a class="hero-link" href="#events">Find your next experience ${icon("arrow")}</a></div>`
    }</div></div>`;
  } catch (err) {
    renderError(err);
  }
}
function renderError(err) {
  app.innerHTML = `<div class="wrap page-error"><h1>A brief intermission.</h1><p>${esc(err.message)}</p><button class="secondary-button" data-action="retry">Try again</button></div>`;
}
async function route() {
  const sequence = ++routeSequence,
    hash = location.hash || "#events";
  if (hash === "#discover") {
    if (state.view !== "events") renderDiscovery();
    document.getElementById("discover")?.scrollIntoView({ behavior: "smooth" });
    return;
  }
  const eventMatch = hash.match(/^#event\/(\d+)$/);
  if (
    state.hold &&
    state.hold.deadline > Date.now() &&
    (!eventMatch || Number(eventMatch[1]) !== state.hold.eventId)
  ) {
    notify(
      "Please confirm or release your reserved seat before leaving checkout.",
    );
    location.hash = `#event/${state.hold.eventId}`;
    return;
  }
  if (hash === "#tickets") {
    await renderTickets(sequence);
  } else if (eventMatch) {
    const id = Number(eventMatch[1]),
      e = state.events.find((e) => e.id === id);
    if (!e) {
      renderError(new Error("This experience could not be found."));
      return;
    }
    state.event = e;
    state.selected = state.hold?.eventId === id ? state.hold.seatId : null;
    state.selectedSeats = state.selected ? [state.selected] : [];
    state.attendeeAge = "";
    admissionPools = [];
    admissionSelection = null;
    admissionQuantity = 1;
    app.innerHTML =
      '<div class="loading-state"><span class="loader"></span>Getting the best view…</div>';
    try {
      const seats = await api(`/api/events/${id}/seats`);
      if (sequence !== routeSequence) return;
      state.seats = seats;
      state.seatCache[id] = seats;
      if (e.admissionMode && e.admissionMode !== "SEATED")
        admissionPools = await api(`/api/events/${id}/admission`);
      if (sequence !== routeSequence) return;
      renderBooking();
    } catch (err) {
      if (sequence !== routeSequence) return;
      renderError(err);
    }
  } else renderDiscovery();
  window.scrollTo({ top: 0, behavior: "instant" });
}
function subscribe() {
  if (subscription) {
    subscription.unsubscribe();
    subscription = null;
  }
  if (!stomp?.connected || state.view !== "booking" || !state.event) return;
  subscription = stomp.subscribe(`/topic/seats/${state.event.id}`, () =>
    refreshSeats(),
  );
}
function connect() {
  try {
    if (typeof SockJS === "undefined" || typeof Stomp === "undefined") return;
    stomp = Stomp.over(new SockJS("/ws"));
    stomp.debug = null;
    stomp.connect(
      {},
      () => {
        state.connection = true;
        subscribe();
        refreshSeats();
      },
      () => {
        state.connection = false;
        setTimeout(connect, 4000);
      },
    );
  } catch {
    setTimeout(connect, 4000);
  }
}
app.addEventListener("click", (event) => {
  const category = event.target.closest("[data-category]");
  if (category) {
    state.category = category.dataset.category;
    document
      .querySelectorAll("[data-category]")
      .forEach((b) =>
        b.classList.toggle("active", b.dataset.category === state.category),
      );
    renderEvents();
    return;
  }
  const button = event.target.closest("[data-seat]");
  if (button && !button.disabled) {
    admissionSelection = null;
    renderAdmissionOptions();
    if (state.hold && state.hold.seatId !== Number(button.dataset.seat)) {
      notify("Release your current seat before choosing another.");
      return;
    }
    const id = Number(button.dataset.seat);
    const index = state.selectedSeats.indexOf(id);
    if (index >= 0) state.selectedSeats.splice(index, 1);
    else if (
      state.selectedSeats.length < Math.min(3, state.event?.perOrderLimit || 3)
    )
      state.selectedSeats.push(id);
    else {
      notify(
        "You can select up to 3 seats per account, or this event's lower limit. Remove a seat to choose another.",
      );
      return;
    }
    state.selected = state.selectedSeats[0] || null;
    renderSeatMap();
    renderCheckout();
    return;
  }
  const action = event.target.closest("[data-action]")?.dataset.action;
  if (action === "hold") acquire();
  if (action === "book") book();
  if (action === "release") release();
  if (action === "retry") state.events.length ? route() : start();
});
dialog.addEventListener("click", (event) => {
  const action = event.target.closest("[data-action]")?.dataset.action;
  if (action === "view-tickets") {
    dialog.close();
    routeTo("#tickets");
  }
  if (action === "close-confirmation") {
    dialog.close();
    routeTo("#events");
  }
});
function renderAccount() {
  accountControls.innerHTML = state.user
    ? `<span class="signed-in-name">${esc(state.user.name)}</span><button class="account-login" data-auth="sign-out">Sign out</button>`
    : `<button class="account-login" data-auth="sign-in">Sign in</button><button class="account-signup" data-auth="sign-up">Create account</button>`;
}
document.addEventListener("click", (event) => {
  const action = event.target.closest("[data-auth]")?.dataset.auth;
  if (action === "sign-out") TickItAuth.signOut();
  else if (action) TickItAuth.open(action);
});
window.addEventListener("tickit:auth-error", (event) =>
  notify(event.detail, true),
);
TickItAuth.onChange((user) => {
  const changed = state.user?.id !== user?.id;
  state.user = user;
  if (changed) {
    clearHold();
    state.selected = null;
  }
  renderAccount();
  if (state.view === "tickets") route();
  else if (state.view === "booking") {
    renderSeatMap();
    renderCheckout();
  }
});
window.addEventListener("hashchange", route);
async function start() {
  try {
    try {
      await TickItAuth.init();
    } catch (error) {
      notify(error.message, true);
    }
    state.user = TickItAuth.user;
    renderAccount();
    const saved = JSON.parse(sessionStorage.getItem("tickit-hold") || "null");
    if (saved && saved.deadline > Date.now() && state.user?.id === saved.userId)
      state.hold = saved;
    else clearHold();
    state.events = await api("/api/events");
    await Promise.all(
      state.events.map(async (e) => {
        if (e.fromPrice == null)
          state.seatCache[e.id] = await api(`/api/events/${e.id}/seats`);
      }),
    );
    await route();
    connect();
  } catch (err) {
    renderError(err);
  }
}
setInterval(updateCountdown, 1000);
setInterval(() => {
  if (!document.hidden) refreshSeats();
}, 5000);
start();

function renderAdmissionOptions() {
  const target = document.getElementById("admissionOptions");
  if (!target) return;
  target.innerHTML =
    admissionPools
      .map(
        (pool) =>
          `<section class="admission-zone"><div class="admission-zone-head"><h3>${esc(pool.name)}</h3><span>${Number(pool.available)} of ${Number(pool.capacity)} places available</span></div>${pool.tickets.map((t) => `<button class="admission-ticket ${admissionSelection === t.id ? "chosen" : ""}" data-admission-ticket="${t.id}" ${t.available <= 0 ? "disabled" : ""}><span><strong>${esc(t.name)}</strong><small>${Number(t.available)} available · unassigned admission</small></span><b>${money(t.price, t.currency)}</b></button>`).join("")}</section>`,
      )
      .join("") ||
    '<p class="map-caption">Admission options are currently unavailable.</p>';
}
function renderAdmissionCheckout() {
  const box = document.getElementById("checkout");
  if (!box) return;
  const type = admissionPools
    .flatMap((p) => p.tickets)
    .find((t) => t.id === admissionSelection);
  if (!type) {
    box.innerHTML =
      '<h2>Your admission</h2><div class="checkout-empty">Choose a ticket type to see admission details.</div>';
    return;
  }
  const maximum = Math.max(
    0,
    Math.min(3, state.event?.perOrderLimit || 3, Number(type.available)),
  );
  admissionQuantity =
    maximum === 0 ? 0 : Math.max(1, Math.min(admissionQuantity, maximum));
  box.innerHTML = `<h2>Your admission</h2><div class="selected-summary"><strong>${esc(type.name)}</strong><small>General admission · no assigned seat</small></div><label class="quantity-label">Tickets<input id="admissionQuantity" type="number" min="${maximum === 0 ? 0 : 1}" max="${maximum}" value="${admissionQuantity}" ${type.available <= 0 ? "disabled" : ""}></label><div class="checkout-line"><span>Price per ticket (${esc(type.currency)})</span><strong>${money(type.price, type.currency)}</strong></div><div class="checkout-line checkout-total"><span>Total</span><strong id="admissionTotal">${money(type.price * admissionQuantity, type.currency)}</strong></div>${ageDeclaration()}${!state.user ? '<button class="primary-button" data-auth="sign-in">Sign in to continue</button>' : '<button class="primary-button" disabled>Checkout unavailable</button>'}<p class="checkout-user">${type.available <= 0 ? "This ticket type is currently sold out or reserved." : "Online checkout is not yet available. Ticket selection does not reserve capacity or confirm a booking."}</p>`;
}
document.addEventListener("click", (event) => {
  const choice = event.target.closest("[data-admission-ticket]");
  if (!choice) return;
  if (state.hold) {
    notify(
      "Release your current seat before choosing another admission option.",
    );
    return;
  }
  admissionSelection = Number(choice.dataset.admissionTicket);
  state.selected = null;
  state.selectedSeats = [];
  renderAdmissionOptions();
  renderSeatMap();
  renderCheckout();
});
document.addEventListener("input", (event) => {
  if (event.target.id !== "admissionQuantity") return;
  const type = admissionPools
    .flatMap((p) => p.tickets)
    .find((t) => t.id === admissionSelection);
  if (!type) return;
  const maximum = Math.max(
    1,
    Math.min(3, state.event?.perOrderLimit || 3, Number(type.available)),
  );
  admissionQuantity = Math.max(
    1,
    Math.min(maximum, Math.trunc(Number(event.target.value)) || 1),
  );
  event.target.value = String(admissionQuantity);
  document.getElementById("admissionTotal").textContent = money(
    type.price * admissionQuantity,
    type.currency,
  );
});

function ageDeclaration() {
  const minimum = state.event?.minimumAge;
  return minimum
    ? `<label class="quantity-label">Youngest guest's age<input id="attendeeAge" type="number" min="${minimum}" max="120" required value="${esc(state.attendeeAge)}" aria-describedby="ageHelp"></label><p id="ageHelp" class="checkout-user">All guests must be ${minimum} or older. This is a declaration; the organizer may check ID at entry.</p>`
    : "";
}
document.addEventListener("input", (event) => {
  if (event.target.id === "attendeeAge") state.attendeeAge = event.target.value;
});
document.addEventListener("click", (event) => {
  const remove = event.target.closest("[data-deselect-seat]");
  if (!remove) return;
  state.selectedSeats = state.selectedSeats.filter(
    (id) => id !== Number(remove.dataset.deselectSeat),
  );
  state.selected = state.selectedSeats[0] || null;
  renderSeatMap();
  renderCheckout();
});
