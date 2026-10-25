const { test, expect } = require("@playwright/test");
const event = {
  id: 901,
  name: "Independent Sessions",
  slug: "independent-sessions",
  description: "Community music and conversation.",
  venueName: "Community Hall",
  venueLocation: "Park Road",
  city: "Mumbai",
  startTime: "2099-06-01T19:00:00",
  startInstant: "2099-06-01T13:30:00Z",
  endInstant: "2099-06-01T16:30:00Z",
  timezone: "Asia/Kolkata",
  category: "COMMUNITY",
  currency: "INR",
  perOrderLimit: 3,
  version: 0,
  minimumAge: 18,
  stageX: 400,
  stageY: 20,
  stageLabel: "Stage",
  totalSeats: 4,
  availableSeats: 4,
  fromPrice: 500,
  organizerName: "Independent Organizer",
  status: "PUBLISHED",
  information: {
    languages: "Hindi, English",
    artists: "<script>window.injected=true</script>",
    ticketInclusions: "One admission and a seat.",
    entryInstructions: "Bring age ID.",
    accessibilityInfo: "Step-free north entrance.",
    faq: "Small bags only.",
  },
};
const seats = [1, 2, 3, 4].map((n) => ({
  seatId: 8000 + n,
  section: "Main",
  row: "A",
  number: n,
  label: `Main · A${n}`,
  x: 80 + n * 30,
  y: 130,
  price: n < 3 ? 500 : 1000,
  currency: "INR",
  status: "FREE",
}));
const orderId = "10000000-0000-4000-8000-000000000001";
async function fixture(page) {
  const errors = [],
    calls = [];
  page.on("pageerror", (e) => errors.push(e.message));
  let saved = [];
  await page.route("**/*", (r) =>
    new URL(r.request().url()).origin === "http://127.0.0.1:4180"
      ? r.continue()
      : r.abort(),
  );
  await page.route("**/js/auth.js", (r) =>
    r.fulfill({
      contentType: "text/javascript",
      body: 'window.TickItAuth={user:{id:101,name:"Fixture account"},config:{checkoutEnabled:false},token:async()=>"fixture",onChange:()=>{},init:async()=>{},open:()=>{}};',
    }),
  );
  await page.route("**/api/**", (r) => {
    const url = new URL(r.request().url()),
      p = url.pathname,
      method = r.request().method();
    calls.push({ p, method, body: r.request().postDataJSON?.() });
    const json = (data) => r.fulfill({ json: data });
    if (p === "/api/events")
      return json([
        event,
        {
          ...event,
          id: 902,
          slug: "delhi-evening",
          name: "Delhi Evening",
          city: "Delhi",
          fromPrice: 2000,
          startInstant: "2099-07-01T13:30:00Z",
          startTime: "2099-07-01T19:00:00",
        },
      ]);
    if (p.endsWith("/seats")) return json(seats);
    if (
      p === "/api/events/901" ||
      p === "/api/events/by-slug/independent-sessions"
    )
      return json(event);
    if (p === "/api/account/saved-events") return json(saved);
    if (p === "/api/account/saved-events/901") {
      saved = method === "DELETE" ? [] : [901];
      return json({ saved: saved.length > 0 });
    }
    if (p === "/api/bookings/me") return json([]);
    if (p === "/api/payments/orders/me")
      return json([
        {
          id: orderId,
          eventId: 901,
          event,
          items: [{ seatId: 8001, label: "Main A1", quantity: 1 }],
          status: "CONFIRMED",
          amount: 50000,
          currency: "INR",
          cancellationDeadline: "2099-05-31T13:30:00Z",
          tickets: [
            {
              id: "20000000-0000-4000-8000-000000000001",
              label: "Main A1",
              ordinal: 1,
              status: "VALID",
            },
          ],
        },
        {
          id: "10000000-0000-4000-8000-000000000002",
          eventId: 903,
          event: {
            ...event,
            id: 903,
            name: "Past Event",
            startInstant: "2020-01-01T13:30:00Z",
            endInstant: "2020-01-01T16:30:00Z",
          },
          items: [{ seatId: 8002, label: "Main A2", quantity: 1 }],
          status: "CONFIRMED",
          amount: 50000,
          currency: "INR",
          tickets: [
            {
              id: "20000000-0000-4000-8000-000000000002",
              label: "Main A2",
              ordinal: 1,
              status: "USED",
            },
          ],
        },
      ]);
    if (p === "/api/account/transfers" || p === "/api/account/received-tickets")
      return json([]);
    if (p === "/api/account/support")
      return json(
        method === "POST"
          ? { id: "support-fixture" }
          : [
              {
                id: "support-fixture",
                order_id: orderId,
                category: "ENTRY",
                message: "Where is entry?",
                status: "RESOLVED",
                reply: "Use the north entrance.",
              },
            ],
      );
    if (p.endsWith("/resend")) return json({ status: "QUEUED" });
    if (p.endsWith("/cancel")) return json({ status: "REFUND_QUEUED" });
    if (p.endsWith("/transfer")) return json({ id: "transfer-fixture" });
    return r.fulfill({ status: 404, json: { error: "Fixture not found" } });
  });
  return { errors, calls };
}
test("details expose authored information safely before inventory selection", async ({
  page,
}) => {
  const f = await fixture(page);
  await page.goto("/events/independent-sessions");
  await expect(
    page.getByRole("heading", { name: "About this experience" }),
  ).toBeVisible();
  await expect(page.locator(".authored-text").first()).toHaveText(
    event.description,
  );
  await expect(page.getByText("Step-free north entrance.")).toBeVisible();
  expect(await page.evaluate(() => window.injected)).toBeUndefined();
  await expect(page.locator("[data-seat]")).toHaveCount(0);
  await page.getByRole("link", { name: "Choose tickets" }).click();
  await expect(page).toHaveURL(/\/book$/);
  await expect(page.locator("[data-seat]")).toHaveCount(4);
  await page.reload();
  await expect(page.locator("[data-seat]")).toHaveCount(4);
  expect(f.errors).toEqual([]);
});
test("city date budget currency and sorting filters persist and reset", async ({
  page,
}) => {
  const f = await fixture(page);
  await page.goto("/");
  await expect(page.locator(".event-card")).toHaveCount(2);
  await page.locator('[data-discovery="city"]').selectOption("Mumbai");
  await expect(page.locator(".event-card")).toHaveCount(1);
  await page.locator('[data-discovery="budget"]').fill("499");
  await page.locator('[data-discovery="budget"]').dispatchEvent("change");
  await expect(page.locator(".event-card")).toHaveCount(0);
  await page.locator('[data-discovery="budget"]').fill("500");
  await page.locator('[data-discovery="budget"]').dispatchEvent("change");
  await page.locator('[data-discovery="from"]').fill("2099-06-01");
  await page.locator('[data-discovery="to"]').fill("2099-06-01");
  await page.locator('[data-discovery="currency"]').selectOption("INR");
  await page.reload();
  await expect(page.locator(".event-card")).toHaveCount(1);
  await expect(page.locator('[data-discovery="city"]')).toHaveValue("Mumbai");
  await page.locator("[data-clear-filters]").click();
  await expect(page.locator(".event-card")).toHaveCount(2);
  expect(f.errors).toEqual([]);
});
test("fit zoom and price highlighting preserve selected seats and total", async ({
  page,
}) => {
  const f = await fixture(page);
  await page.goto("/events/independent-sessions/book");
  await expect(page.locator("[data-seat]")).toHaveCount(4);
  await page.getByRole("button", { name: "Fit map", exact: true }).click();
  const before = await page.locator("#mapScale").textContent();
  await page.getByRole("button", { name: "Zoom in seat map" }).click();
  expect(await page.locator("#mapScale").textContent()).not.toBe(before);
  await page.locator("#seatPrice").selectOption("500");
  await expect(page.locator(".price-muted")).toHaveCount(2);
  await page.locator('[data-seat="8001"]').click();
  await expect(page.locator("#checkout")).toContainText("₹500");
  await expect(page.locator("#seatPrice")).toHaveValue("500");
  await expect(page.locator(".seat.selected")).toHaveCount(1);
  if (test.info().project.name === "mobile") {
    await page.locator("#mobileBookingSummary").click();
    await expect(page.locator("#checkout")).toBeFocused();
  }
  expect(f.errors).toEqual([]);
});
test("saved events are account backed and filter discovery", async ({
  page,
}) => {
  const f = await fixture(page);
  await page.goto("/events/independent-sessions");
  await page.getByRole("button", { name: "Save event", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Saved", exact: true }),
  ).toHaveAttribute("aria-pressed", "true");
  await page
    .getByRole("link", { name: "← All experiences", exact: true })
    .click();
  await page.getByRole("button", { name: "Saved events", exact: true }).click();
  await expect(page.locator(".event-card")).toHaveCount(1);
  expect(
    f.calls.some(
      (c) => c.p === "/api/account/saved-events/901" && c.method === "PUT",
    ),
  ).toBe(true);
  expect(f.errors).toEqual([]);
});
test("ticket dashboard filters history and downloads a calendar", async ({
  page,
}) => {
  const f = await fixture(page);
  await page.goto("/#tickets");
  await expect(page.locator("[data-ticket-bucket]")).toHaveCount(2);
  await page.getByRole("button", { name: "Past", exact: true }).click();
  await expect(page.locator("[data-ticket-bucket]:visible")).toHaveCount(1);
  await expect(page.locator("[data-ticket-bucket]:visible")).toContainText(
    "Past Event",
  );
  await page.getByRole("button", { name: "Upcoming", exact: true }).click();
  await expect(page.locator("[data-ticket-bucket]:visible")).toHaveCount(1);
  const dl = page.waitForEvent("download");
  await page.getByRole("button", { name: "Add to calendar" }).first().click();
  await expect((await dl).suggestedFilename()).toBe("tickit-event-901.ics");
  expect(f.errors).toEqual([]);
});
test("order help queues resend and creates a tracked support request", async ({
  page,
}) => {
  const f = await fixture(page);
  await page.goto("/#tickets");
  await page.locator(`[data-order-help="${orderId}"]`).click();
  await page.getByRole("button", { name: "Resend confirmation email" }).click();
  await expect(page.locator("#customerToolStatus")).toContainText("queued");
  await page.locator("#supportForm textarea").fill("Where is entry?");
  await page.getByRole("button", { name: "Send request", exact: true }).click();
  await expect(page.locator("#customerToolStatus")).toContainText(
    "Request received",
  );
  await page.getByRole("button", { name: "Close ×", exact: true }).click();
  await page.getByRole("button", { name: "My support requests" }).click();
  await expect(page.getByText("Use the north entrance.")).toBeVisible();
  expect(
    f.calls.find((c) => c.p === "/api/account/support" && c.method === "POST")
      .body.orderId,
  ).toBe(orderId);
  expect(f.errors).toEqual([]);
});
test("transfer and cancellation require explicit customer actions", async ({
  page,
}) => {
  const f = await fixture(page);
  await page.goto("/#tickets");
  await page
    .getByRole("button", { name: "Transfer ticket", exact: true })
    .first()
    .click();
  await page
    .locator("#ticketTransferForm input")
    .fill("recipient@example.test");
  await page
    .getByRole("button", { name: "Offer transfer", exact: true })
    .click();
  await expect(page.locator("#customerToolDialog")).toHaveCount(0);
  await page.locator(`[data-order-help="${orderId}"]`).click();
  await page
    .getByRole("button", { name: "Cancel booking", exact: true })
    .click();
  expect(f.calls.some((c) => c.p.endsWith("/cancel"))).toBe(false);
  await page
    .getByRole("button", { name: "Cancel order & request full refund" })
    .click();
  await expect(page.locator("#customerToolDialog")).toHaveCount(0);
  expect(
    f.calls.some((c) => c.p.endsWith("/cancel") && c.method === "POST"),
  ).toBe(true);
  expect(f.errors).toEqual([]);
});

test("event information and discovery fit the viewport", async ({ page }) => {
  await fixture(page);
  await page.goto("/events/independent-sessions");
  await expect(
    page.getByRole("heading", { name: "About this experience" }),
  ).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: `../../work/experience-details-${test.info().project.name}.png`,
    fullPage: true,
  });
  await page.goto("/");
  await expect(page.locator(".event-card")).toHaveCount(2);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: `../../work/experience-discovery-${test.info().project.name}.png`,
    fullPage: true,
  });
});
test("developer support inbox records a reply", async ({ page }) => {
  const f = await fixture(page);
  let resolved = false;
  await page.route("**/api/developer/overview", (r) =>
    r.fulfill({
      json: {
        catalog: { events: 2 },
        services: { postgresql: true, redis: true, objectStorage: true },
        integrations: { authentication: true, payments: true, email: true },
        checkedAt: "2026-10-25",
      },
    }),
  );
  await page.route("**/api/developer/support", (r) =>
    r.fulfill({
      json: [
        {
          id: orderId,
          order_id: orderId,
          category: "ENTRY",
          message: "Where is entry?",
          status: resolved ? "RESOLVED" : "OPEN",
          reply: resolved ? "Use the north entrance." : null,
        },
      ],
    }),
  );
  await page.route(`**/api/developer/support/${orderId}/reply`, (r) => {
    resolved = r.request().postDataJSON().reply === "Use the north entrance.";
    return r.fulfill({ json: { status: "RESOLVED" } });
  });
  await page.goto("/developer/");
  await page.locator('[data-section="support"]').click();
  await page
    .locator("[data-support-reply] textarea")
    .fill("Use the north entrance.");
  await page.getByRole("button", { name: "Reply & resolve" }).click();
  await expect(page.locator("#supportQueue")).toContainText("RESOLVED");
  expect(resolved).toBe(true);
  expect(f.errors).toEqual([]);
});
