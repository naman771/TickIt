"use strict";
// Presentation helpers share the existing booking state; they never reserve inventory.
let discoveryPreferences;
function preferences() {
  if (!discoveryPreferences) {
    try {
      discoveryPreferences =
        JSON.parse(localStorage.getItem("tickit.discovery.v1")) || {};
    } catch {
      discoveryPreferences = {};
    }
    if (
      typeof discoveryPreferences !== "object" ||
      Array.isArray(discoveryPreferences)
    )
      discoveryPreferences = {};
  }
  return discoveryPreferences;
}
function discoveryTools() {
  const p = preferences();
  const cities = [...new Set(state.events.map(city).filter(Boolean))].sort();
  return `<div class="discovery-options" aria-label="Discovery filters"><label>City<select data-discovery="city"><option value="">All cities</option>${cities.map((c) => `<option ${p.city === c ? "selected" : ""} value="${esc(c)}">${esc(c)}</option>`).join("")}</select></label><label>From date<input type="date" data-discovery="from" value="${esc(/^\d{4}-\d{2}-\d{2}$/.test(p.from) ? p.from : "")}"></label><label>To date<input type="date" data-discovery="to" value="${esc(/^\d{4}-\d{2}-\d{2}$/.test(p.to) ? p.to : "")}"></label><label>Maximum ticket price<input type="number" data-discovery="budget" min="0" step="1" placeholder="Any price" value="${esc(Number.isFinite(Number(p.budget)) ? p.budget : "")}"></label><label>Currency<select data-discovery="currency"><option value="">Any currency</option>${[
    ...new Set(state.events.map((e) => e.currency || "USD")),
  ]
    .sort()
    .map(
      (c) =>
        `<option ${p.currency === c ? "selected" : ""} value="${esc(c)}">${esc(c)}</option>`,
    )
    .join(
      "",
    )}</select></label><label>Sort by<select data-discovery="sort"><option value="date">Soonest first</option><option value="price" ${p.sort === "price" ? "selected" : ""}>Price: low to high</option><option value="name" ${p.sort === "name" ? "selected" : ""}>Event name</option></select></label><button class="secondary-button" data-clear-filters>Reset filters</button><button class="secondary-button" data-saved-only aria-pressed="${onlySaved}">${onlySaved ? "Showing saved events" : "Saved events"}</button></div><p class="discovery-count" id="discoveryCount" role="status"></p>`;
}
function localEventDate(e) {
  // Local event calendar date: never interpret an offset-less datetime in the visitor's timezone.
  if (!e.startInstant) return String(e.startTime || "").slice(0, 10);
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: e.timezone || "Asia/Kolkata",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(new Date(e.startInstant));
  return ["year", "month", "day"]
    .map((k) => parts.find((p) => p.type === k).value)
    .join("-");
}
function discoverMatches(events) {
  const p = preferences();
  const matches = events.filter(
    (e) =>
      (!onlySaved || savedEvents.has(e.id)) &&
      (!p.city || city(e) === p.city) &&
      (!p.from || localEventDate(e) >= p.from) &&
      (!p.to || localEventDate(e) <= p.to) &&
      (!p.currency || (e.currency || "USD") === p.currency) &&
      (p.budget == null || p.budget === "" || fromPrice(e) <= Number(p.budget)),
  );
  matches.sort((a, b) =>
    p.sort === "name"
      ? a.name.localeCompare(b.name)
      : p.sort === "price"
        ? (a.currency || "USD").localeCompare(b.currency || "USD") ||
          fromPrice(a) - fromPrice(b)
        : String(a.startInstant || a.startTime).localeCompare(
            String(b.startInstant || b.startTime),
          ),
  );
  const count = document.getElementById("discoveryCount");
  if (count)
    count.textContent = `${matches.length} experience${matches.length === 1 ? "" : "s"} found${p.sort === "price" && !p.currency ? " · Prices grouped by currency" : ""}${p.from && p.to && p.from > p.to ? " · From date must be before to date" : ""}`;
  return matches;
}
function bookingUrl(e) {
  return e.slug ? `${eventUrl(e)}/book` : eventUrl(e);
}
function renderEventDetails() {
  loadSavedEvents();
  const e = state.event,
    info = e.information || {};
  state.view = "details";
  setNav("events");
  const sections = [
    ["About this experience", e.description],
    ["Lineup", info.artists],
    ["Languages", info.languages],
    ["Your ticket includes", info.ticketInclusions],
    ["Before you arrive", info.entryInstructions],
    ["Accessibility", info.accessibilityInfo],
    ["Frequently asked questions", info.faq],
  ].filter(([, text]) => text?.trim());
  app.innerHTML = `<div class="wrap event-details"><a class="back-link" href="#events">← All experiences</a><section class="details-hero"><img src="${esc(photo(e))}" alt="${esc(e.name)} event artwork"><div><div class="eyebrow">${esc(categoryName(e))} / ${esc(city(e))}</div><h1>${esc(e.name)}</h1><p>${esc(when(e.startInstant || e.startTime, e.timezone))} · ${esc(hour(e.startInstant || e.startTime, e.timezone))} (${esc(e.timezone || "Asia/Kolkata")})</p><p>${esc(e.venueName)} · ${esc(e.venueLocation)}</p>${e.endInstant ? `<p>Ends ${esc(when(e.endInstant, e.timezone))} · ${esc(hour(e.endInstant, e.timezone))}</p>` : ""}${e.organizerName ? `<p>Organized by <strong>${esc(e.organizerName)}</strong></p>` : ""}<div class="detail-chips"><span>${esc(e.admissionMode === "GENERAL" ? "Unassigned admission" : e.admissionMode === "MIXED" ? "Seats & unassigned admission" : "Assigned seats")}</span><span>${e.minimumAge ? `Ages ${Number(e.minimumAge)}+ · Bring valid ID` : "No age restriction specified"}</span><span>Up to ${Math.min(3, e.perOrderLimit || 3)} tickets per account</span></div>${saveEventButton(e)}<div class="details-book"><div><small>Tickets from</small><strong>${Number.isFinite(fromPrice(e)) ? money(fromPrice(e), e.currency || "USD") : "Price at selection"}</strong></div><a class="primary-button" href="${esc(bookingUrl(e))}">Choose tickets ${icon("arrow")}</a></div></div></section><div class="details-content">${sections.map(([title, text]) => `<section><h2>${title}</h2><p class="authored-text">${esc(text)}</p></section>`).join("")}<section><h2>Venue & arrival</h2><p>${esc(e.venueName)}<br>${esc(e.venueLocation)} · ${esc(city(e))}</p>${venueLink(e)} ${calendarLink(e)}</section><section><h2>Booking & entry</h2><p>Availability is checked when you reserve. A selection alone does not hold tickets. Entry opens six hours before the event; each admission QR permits one entry.</p><p>${e.cancellationCutoffHours ? `Full order cancellation is available until ${Number(e.cancellationCutoffHours)} hours before the event starts. Your exact deadline is saved at checkout. Used or transferred tickets cannot be cancelled through self-service.` : "This event does not offer customer cancellation."} If the organizer cancels the event, eligible payments enter the refund workflow. Bank processing and provider failures may delay completion.</p></section></div></div>`;
}
function venueLink(e) {
  return `<a class="secondary-button" target="_blank" rel="noopener noreferrer" href="https://www.google.com/maps/search/?api=1&query=${encodeURIComponent([e.venueName, e.venueLocation, city(e)].filter(Boolean).join(", "))}">Directions ↗</a>`;
}
function calendarLink(e) {
  return e.startInstant
    ? `<button class="secondary-button" data-calendar-event="${Number(e.id)}">Add to calendar</button>`
    : "";
}
function downloadCalendar(e) {
  if (!e?.startInstant) return;
  const escape = (v) =>
    String(v || "")
      .replace(/\\/g, "\\\\")
      .replace(/\r?\n/g, "\\n")
      .replace(/;/g, "\\;")
      .replace(/,/g, "\\,");
  const stamp = (v) =>
    new Date(v)
      .toISOString()
      .replace(/[-:]/g, "")
      .replace(/\.\d{3}Z$/, "Z");
  const lines = [
    "BEGIN:VCALENDAR",
    "VERSION:2.0",
    "PRODID:-//TickIt//Events//EN",
    "BEGIN:VEVENT",
    `UID:event-${Number(e.id)}@tickit`,
    `DTSTAMP:${stamp(Date.now())}`,
    `DTSTART:${stamp(e.startInstant)}`,
    ...(e.endInstant ? [`DTEND:${stamp(e.endInstant)}`] : []),
    `SUMMARY:${escape(e.name)}`,
    `LOCATION:${escape([e.venueName, e.venueLocation, city(e)].filter(Boolean).join(", "))}`,
    `DESCRIPTION:${escape(e.description)}`,
    "END:VEVENT",
    "END:VCALENDAR",
  ];
  // Fold by UTF-8 octets (RFC 5545), including multibyte event names.
  const folded =
    lines
      .map((line) => {
        let out = "",
          part = "",
          bytes = 0;
        for (const ch of line) {
          const n = new TextEncoder().encode(ch).length;
          if (bytes + n > 73) {
            out += part + "\r\n ";
            part = "";
            bytes = 1;
          }
          part += ch;
          bytes += n;
        }
        return out + part;
      })
      .join("\r\n") + "\r\n";
  const url = URL.createObjectURL(
    new Blob([folded], { type: "text/calendar;charset=utf-8" }),
  );
  const a = document.createElement("a");
  a.href = url;
  a.download = `tickit-event-${Number(e.id)}.ics`;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
let seatMapScale = 1,
  seatMapEvent = null,
  seatPrice = "";
function enhanceSeatMap(reset = false) {
  const canvas = document.querySelector(".positioned-map"),
    scroll = document.querySelector(".map-scroll");
  if (!canvas || !scroll) return;
  if (seatMapEvent !== state.event.id) {
    seatMapEvent = state.event.id;
    seatMapScale = 1;
    seatPrice = "";
  }
  if (!document.getElementById("mapTools")) {
    scroll.insertAdjacentHTML(
      "beforebegin",
      `<div id="mapTools" class="map-tools" role="group" aria-label="Seat map controls"><button class="secondary-button" data-map-zoom="fit">Fit map</button><button class="secondary-button" data-map-zoom="out" aria-label="Zoom out seat map">−</button><button class="secondary-button" data-map-zoom="in" aria-label="Zoom in seat map">+</button><label>Highlight price<select id="seatPrice"><option value="">All prices</option>${[
        ...new Set(state.seats.map((s) => Number(s.price))),
      ]
        .sort((a, b) => a - b)
        .map((p) => `<option value="${p}">${money(p)}</option>`)
        .join(
          "",
        )}</select></label><span id="mapScale" role="status"></span></div>`,
    );
  }
  if (reset)
    seatMapScale = Math.max(
      0.15,
      Math.min(1, (scroll.clientWidth - 12) / 1000),
    );
  // CSS zoom changes layout bounds as well as appearance, retaining accurate pointer targeting.
  canvas.style.zoom = seatMapScale;
  const label = document.getElementById("mapScale");
  if (label) label.textContent = `${Math.round(seatMapScale * 100)}%`;
  const price = document.getElementById("seatPrice");
  if (price) price.value = seatPrice;
  canvas.querySelectorAll("[data-seat]").forEach((b) => {
    const seat = state.seats.find((s) => s.seatId === Number(b.dataset.seat));
    b.classList.toggle(
      "price-muted",
      seatPrice !== "" && Number(seat?.price) !== Number(seatPrice),
    );
  });
}
function mobileBookingSummary() {
  document.getElementById("mobileBookingSummary")?.remove();
  const total = document.querySelector("#checkout .checkout-total");
  if (state.view !== "booking" || !total) return;
  const node = document.createElement("button");
  node.id = "mobileBookingSummary";
  node.className = "mobile-booking-summary";
  node.type = "button";
  node.textContent = `${total.textContent.trim()} · View selection`;
  node.addEventListener("click", () => {
    const box = document.getElementById("checkout");
    box.scrollIntoView({ behavior: "smooth", block: "start" });
    box.setAttribute("tabindex", "-1");
    box.focus({ preventScroll: true });
  });
  app.append(node);
}
document.addEventListener("change", (event) => {
  if (event.target.matches("[data-discovery]")) {
    preferences()[event.target.dataset.discovery] = event.target.value;
    try {
      localStorage.setItem(
        "tickit.discovery.v1",
        JSON.stringify(preferences()),
      );
    } catch {}
    renderEvents();
  }
  if (event.target.id === "seatPrice") {
    seatPrice = event.target.value;
    enhanceSeatMap();
  }
});
document.addEventListener("click", (event) => {
  if (event.target.closest("[data-clear-filters]")) {
    discoveryPreferences = {};
    try {
      localStorage.removeItem("tickit.discovery.v1");
    } catch {}
    state.search = "";
    state.category = "all";
    renderDiscovery();
  }
  const zoom = event.target.closest("[data-map-zoom]");
  if (zoom) {
    seatMapScale = Math.max(
      0.15,
      Math.min(2, seatMapScale + (zoom.dataset.mapZoom === "in" ? 0.2 : -0.2)),
    );
    enhanceSeatMap(zoom.dataset.mapZoom === "fit");
  }
  const cal = event.target.closest("[data-calendar-event]");
  if (cal)
    downloadCalendar(
      state.events.find((e) => e.id === Number(cal.dataset.calendarEvent)) ||
        state.ticketEvents?.find(
          (e) => e.id === Number(cal.dataset.calendarEvent),
        ) ||
        state.event,
    );
});
