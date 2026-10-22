// TickIt Interactive Client Application
let currentEvent = null;
let currentSeats = [];
let selectedSeat = null;
let currentHold = null; // { seatId, timerInterval, secondsRemaining }
let stompClient = null;
let currentSubscription = null;

const USERS = [
  { id: 1, name: "Alex Morgan", email: "alex@tickit.live" },
  { id: 2, name: "Sam Taylor", email: "sam@tickit.live" },
  { id: 3, name: "Flash Bot (Abusive)", email: "flashbot@loadtest.local" }
];

let activeUser = USERS[0];

// DOM elements
const eventsContainer = document.getElementById("eventsContainer");
const seatGrid = document.getElementById("seatGrid");
const currentEventName = document.getElementById("currentEventName");
const currentVenue = document.getElementById("currentVenue");
const availableBadge = document.getElementById("availableBadge");

const selectedSeatLabel = document.getElementById("selectedSeatLabel");
const selectedSeatPrice = document.getElementById("selectedSeatPrice");
const holdBtn = document.getElementById("holdBtn");
const bookBtn = document.getElementById("bookBtn");
const releaseBtn = document.getElementById("releaseBtn");
const holdTimerBox = document.getElementById("holdTimerBox");
const holdTimerText = document.getElementById("holdTimerText");
const holdProgressFill = document.getElementById("holdProgressFill");

const userSelect = document.getElementById("userSelect");
const botRunBtn = document.getElementById("botRunBtn");
const botSentCount = document.getElementById("botSentCount");
const botSuccessCount = document.getElementById("botSuccessCount");
const botBlockedCount = document.getElementById("botBlockedCount");
const botLog = document.getElementById("botLog");

// Initialize application
document.addEventListener("DOMContentLoaded", () => {
  setupUserSelector();
  initWebSocket();
  loadEvents();
  setupEventListeners();
});

function setupUserSelector() {
  userSelect.innerHTML = USERS.map(u => 
    `<option value="${u.id}">${u.name} (${u.email})</option>`
  ).join("");

  userSelect.addEventListener("change", (e) => {
    activeUser = USERS.find(u => u.id == e.target.value) || USERS[0];
    showToast(`Switched active user to: ${activeUser.name}`, "success");
  });
}

function setupEventListeners() {
  holdBtn.addEventListener("click", handleAcquireHold);
  releaseBtn.addEventListener("click", handleReleaseHold);
  bookBtn.addEventListener("click", handleConfirmBooking);
  botRunBtn.addEventListener("click", runBotSimulation);
}

// ── WebSocket Connection ─────────────────────────────────
function initWebSocket() {
  try {
    const socket = new SockJS("/ws");
    stompClient = Stomp.over(socket);
    stompClient.debug = null; // quiet console logs

    stompClient.connect({}, () => {
      console.log("Connected to TickIt WebSocket");
      if (currentEvent) {
        subscribeToEvent(currentEvent.id);
      }
    }, (err) => {
      console.warn("WebSocket disconnected, retrying in 4s...", err);
      setTimeout(initWebSocket, 4000);
    });
  } catch (e) {
    console.error("WebSocket init error:", e);
  }
}

function subscribeToEvent(eventId) {
  if (!stompClient || !stompClient.connected) return;
  if (currentSubscription) {
    currentSubscription.unsubscribe();
  }

  currentSubscription = stompClient.subscribe(`/topic/seats/${eventId}`, (message) => {
    const update = JSON.parse(message.body);
    handleSeatUpdate(update);
  });
}

function handleSeatUpdate(update) {
  const seatIdx = currentSeats.findIndex(s => s.seatId === update.seatId);
  if (seatIdx !== -1) {
    currentSeats[seatIdx].status = update.status;
  }

  // Update DOM element
  const seatEl = document.querySelector(`.seat[data-id="${update.seatId}"]`);
  if (seatEl) {
    seatEl.className = `seat ${update.status.toLowerCase()}`;
    if (selectedSeat && selectedSeat.seatId === update.seatId && update.status !== "FREE") {
      if (update.status === "BOOKED") {
        selectedSeat = null;
        updateCheckoutUI();
      }
    }
  }

  showToast(`Live update: Seat ${update.seatLabel} is now ${update.status}`, 
    update.status === "BOOKED" ? "warn" : "success");
}

// ── Load Events ──────────────────────────────────────────
async function loadEvents() {
  try {
    const res = await fetch("/api/events");
    const events = await res.json();
    renderEvents(events);
    if (events.length > 0) {
      selectEvent(events[0]);
    }
  } catch (err) {
    console.error("Failed to fetch events:", err);
    showToast("Failed to connect to backend API", "error");
  }
}

function renderEvents(events) {
  eventsContainer.innerHTML = events.map(evt => `
    <div class="event-card ${currentEvent && currentEvent.id === evt.id ? 'active' : ''}" 
         onclick="selectEventById(${evt.id})">
      <div class="event-img-wrap">
        <img class="event-img" src="${evt.imageUrl}" alt="${evt.name}">
        <span class="event-chip">${evt.availableSeats} Seats Left</span>
      </div>
      <div class="event-body">
        <h3 class="event-name">${evt.name}</h3>
        <div class="event-meta">
          <span class="event-meta-item">📍 ${evt.venueName}</span>
          <span class="event-meta-item">📅 ${new Date(evt.startTime).toLocaleDateString()}</span>
        </div>
        <div class="event-stats">
          <span>Tickets from $${evt.totalSeats > 0 ? '120.00' : '0'}</span>
          <span class="avail-count">${evt.availableSeats} / ${evt.totalSeats} free</span>
        </div>
      </div>
    </div>
  `).join("");
}

window.selectEventById = function(id) {
  fetch(`/api/events/${id}`)
    .then(r => r.json())
    .then(evt => selectEvent(evt));
};

function selectEvent(event) {
  currentEvent = event;
  selectedSeat = null;
  clearHoldTimer();
  updateCheckoutUI();

  currentEventName.textContent = event.name;
  currentVenue.textContent = `📍 ${event.venueName}, ${event.venueLocation}`;

  document.querySelectorAll(".event-card").forEach(el => {
    el.classList.toggle("active", el.innerHTML.includes(event.name));
  });

  subscribeToEvent(event.id);
  loadSeats(event.id);
}

// ── Load & Render Seats ──────────────────────────────────
async function loadSeats(eventId) {
  try {
    const res = await fetch(`/api/events/${eventId}/seats`);
    currentSeats = await res.json();
    renderSeats(currentSeats);
  } catch (err) {
    console.error("Failed to load seats:", err);
  }
}

function renderSeats(seats) {
  // Group by row
  const rows = {};
  seats.forEach(s => {
    if (!rows[s.row]) rows[s.row] = [];
    rows[s.row].push(s);
  });

  let html = "";
  Object.keys(rows).sort().forEach(r => {
    html += `
      <div class="seat-row">
        <span class="row-label">${r}</span>
        <div class="row-seats">
          ${rows[r].map(seat => `
            <div class="seat ${seat.status.toLowerCase()} ${selectedSeat && selectedSeat.seatId === seat.seatId ? 'selected' : ''}" 
                 data-id="${seat.seatId}"
                 data-label="${seat.label}"
                 data-price="${seat.price}"
                 data-status="${seat.status}"
                 onclick="onSeatClick(${seat.seatId})">
              ${seat.number}
            </div>
          `).join("")}
        </div>
        <span class="row-label">${r}</span>
      </div>
    `;
  });

  seatGrid.innerHTML = html;
  updateAvailableBadge();
}

function updateAvailableBadge() {
  const freeCount = currentSeats.filter(s => s.status === "FREE").length;
  availableBadge.textContent = `${freeCount} Seats Available`;
}

window.onSeatClick = function(seatId) {
  const seat = currentSeats.find(s => s.seatId === seatId);
  if (!seat) return;

  if (seat.status === "BOOKED") {
    showToast("This seat is already booked permanently.", "error");
    return;
  }

  if (seat.status === "HELD" && (!currentHold || currentHold.seatId !== seatId)) {
    showToast("This seat is currently held by someone. Wait or pick another.", "warn");
    return;
  }

  // Toggle selection
  if (selectedSeat && selectedSeat.seatId === seatId) {
    selectedSeat = null;
  } else {
    selectedSeat = seat;
  }

  // Update selection style
  document.querySelectorAll(".seat").forEach(el => {
    const id = parseInt(el.getAttribute("data-id"));
    if (selectedSeat && id === selectedSeat.seatId) {
      el.classList.add("selected");
    } else {
      el.classList.remove("selected");
    }
  });

  updateCheckoutUI();
};

function updateCheckoutUI() {
  if (!selectedSeat) {
    selectedSeatLabel.textContent = "None selected";
    selectedSeatPrice.textContent = "$0.00";
    holdBtn.disabled = true;
    bookBtn.disabled = true;
    releaseBtn.style.display = "none";
    return;
  }

  selectedSeatLabel.textContent = `Row ${selectedSeat.row}, Seat ${selectedSeat.number} (${selectedSeat.label})`;
  selectedSeatPrice.textContent = `$${parseFloat(selectedSeat.price).toFixed(2)}`;

  if (currentHold && currentHold.seatId === selectedSeat.seatId) {
    holdBtn.disabled = true;
    bookBtn.disabled = false;
    releaseBtn.style.display = "block";
  } else {
    holdBtn.disabled = (selectedSeat.status !== "FREE");
    bookBtn.disabled = true;
    releaseBtn.style.display = "none";
  }
}

// ── Hold Flow ────────────────────────────────────────────
async function handleAcquireHold() {
  if (!selectedSeat || !currentEvent) return;

  holdBtn.disabled = true;
  holdBtn.innerHTML = "⏳ Holding...";

  try {
    const res = await fetch("/api/holds", {
      method: "POST",
      headers: { 
        "Content-Type": "application/json",
        "X-User-Id": activeUser.id.toString()
      },
      body: JSON.stringify({
        eventId: currentEvent.id,
        seatId: selectedSeat.seatId,
        userId: activeUser.id
      })
    });

    const data = await res.json();

    if (res.ok) {
      showToast(`Hold acquired on Seat ${selectedSeat.label}! You have 120s to checkout.`, "success");
      startHoldTimer(selectedSeat.seatId, 120);
      updateCheckoutUI();
    } else if (res.status === 429) {
      showToast("Rate limit exceeded! Slow down bot.", "error");
    } else {
      showToast(data.error || "Could not hold seat.", "error");
    }
  } catch (err) {
    showToast("Network error holding seat", "error");
  } finally {
    holdBtn.innerHTML = "🔒 1. Hold Seat (120s)";
  }
}

function startHoldTimer(seatId, totalSeconds) {
  clearHoldTimer();

  let remaining = totalSeconds;
  holdTimerBox.classList.add("active");

  const interval = setInterval(() => {
    remaining--;
    const mins = Math.floor(remaining / 60);
    const secs = remaining % 60;
    holdTimerText.textContent = `${mins}:${secs < 10 ? '0' : ''}${secs}`;
    holdProgressFill.style.width = `${(remaining / totalSeconds) * 100}%`;

    if (remaining <= 0) {
      clearHoldTimer();
      showToast(`Hold expired on seat ${selectedSeat ? selectedSeat.label : ''}!`, "warn");
      loadSeats(currentEvent.id);
    }
  }, 1000);

  currentHold = { seatId, timerInterval: interval };
}

function clearHoldTimer() {
  if (currentHold && currentHold.timerInterval) {
    clearInterval(currentHold.timerInterval);
  }
  currentHold = null;
  holdTimerBox.classList.remove("active");
}

async function handleReleaseHold() {
  if (!selectedSeat || !currentEvent) return;
  try {
    await fetch(`/api/holds/${currentEvent.id}/${selectedSeat.seatId}?userId=${activeUser.id}`, {
      method: "DELETE"
    });
    clearHoldTimer();
    showToast("Hold released manually.", "success");
    loadSeats(currentEvent.id);
    updateCheckoutUI();
  } catch (e) {
    console.error("Error releasing hold:", e);
  }
}

// ── Booking Flow ─────────────────────────────────────────
async function handleConfirmBooking() {
  if (!selectedSeat || !currentEvent) return;

  bookBtn.disabled = true;
  bookBtn.innerHTML = "💳 Processing...";

  try {
    const res = await fetch("/api/bookings", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-User-Id": activeUser.id.toString()
      },
      body: JSON.stringify({
        eventId: currentEvent.id,
        seatId: selectedSeat.seatId,
        userId: activeUser.id
      })
    });

    const data = await res.json();

    if (res.ok) {
      clearHoldTimer();
      showToast(`🎉 Success! Booking #${data.bookingId} confirmed for Seat ${data.seatLabel}!`, "success");
      selectedSeat = null;
      updateCheckoutUI();
      loadSeats(currentEvent.id);
    } else if (res.status === 429) {
      showToast("Rate limit exceeded! Slow down bot.", "error");
      bookBtn.disabled = false;
    } else {
      showToast(data.error || "Booking failed.", "error");
      bookBtn.disabled = false;
    }
  } catch (err) {
    showToast("Network error processing booking", "error");
    bookBtn.disabled = false;
  } finally {
    bookBtn.innerHTML = "⚡ 2. Confirm Booking";
  }
}

// ── Bot Attack / Rate Limiter Simulation ──────────────────
async function runBotSimulation() {
  if (!currentEvent || currentSeats.length === 0) return;

  botRunBtn.disabled = true;
  botRunBtn.innerHTML = "🚀 Attack in progress...";

  let sent = 0;
  let success = 0;
  let blocked = 0;

  botSentCount.textContent = "0";
  botSuccessCount.textContent = "0";
  botBlockedCount.textContent = "0";
  botLog.innerHTML = "";

  const targetSeat = currentSeats.find(s => s.status === "FREE") || currentSeats[0];
  const totalRequests = 25; // Exceeds our 20 bucket capacity

  logBotMsg(`Initiating high-velocity bot spike: ${totalRequests} requests against seat ${targetSeat.label}...`, "ok");

  const requests = [];

  for (let i = 1; i <= totalRequests; i++) {
    const promise = (async (reqNum) => {
      sent++;
      botSentCount.textContent = sent;

      try {
        const res = await fetch("/api/holds", {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "X-User-Id": "3" // Abusive Flash Bot ID
          },
          body: JSON.stringify({
            eventId: currentEvent.id,
            seatId: targetSeat.seatId,
            userId: 3
          })
        });

        if (res.status === 200) {
          success++;
          botSuccessCount.textContent = success;
          logBotMsg(`Req #${reqNum}: [200 OK] Hold acquired!`, "ok");
        } else if (res.status === 429) {
          blocked++;
          botBlockedCount.textContent = blocked;
          logBotMsg(`Req #${reqNum}: [429 BLOCKED] Redis Token-Bucket rate limit!`, "rate-limited");
        } else if (res.status === 409) {
          blocked++;
          botBlockedCount.textContent = blocked;
          logBotMsg(`Req #${reqNum}: [409 CONFLICT] Seat held by another thread!`, "conflict");
        } else {
          blocked++;
          botBlockedCount.textContent = blocked;
          logBotMsg(`Req #${reqNum}: [HTTP ${res.status}] Rejected`, "conflict");
        }
      } catch (err) {
        blocked++;
        botBlockedCount.textContent = blocked;
        logBotMsg(`Req #${reqNum}: Connection dropped`, "conflict");
      }
    })(i);

    requests.push(promise);
  }

  await Promise.all(requests);

  logBotMsg(`Spike complete! Redis + OCC successfully protected the booking pipeline.`, "ok");
  botRunBtn.disabled = false;
  botRunBtn.innerHTML = "🚀 Launch Bot Stress Test (25 Reqs/sec)";
  loadSeats(currentEvent.id);
}

function logBotMsg(msg, type) {
  const line = document.createElement("div");
  line.className = `log-entry ${type}`;
  line.textContent = `[${new Date().toLocaleTimeString()}] ${msg}`;
  botLog.prepend(line);
}

// ── Toasts ───────────────────────────────────────────────
function showToast(message, type = "success") {
  const container = document.getElementById("toastContainer");
  const toast = document.createElement("div");
  toast.className = `toast ${type}`;
  toast.innerHTML = `<span>${type === 'error' ? '❌' : (type === 'warn' ? '⚠️' : '✅')}</span><span>${message}</span>`;
  container.appendChild(toast);

  setTimeout(() => {
    toast.style.opacity = "0";
    toast.style.transform = "translateX(100%)";
    toast.style.transition = "all 0.3s ease";
    setTimeout(() => toast.remove(), 300);
  }, 4000);
}
