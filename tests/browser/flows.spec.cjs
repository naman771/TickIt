const { test, expect } = require("@playwright/test");
const base = "http://127.0.0.1:4180";
const auth =
  'window.TickItAuth={user:{id:101,name:"Fixture account"},config:{checkoutEnabled:false},token:async()=>"fixture",onChange:()=>{},init:async()=>{},open:()=>{},signOut:async()=>{}};';
const common = {
  name: "Independent Sessions",
  slug: "independent-sessions-mumbai",
  description: "Community music and conversation.",
  venueName: "Community Hall",
  venueLocation: "Park Road",
  city: "Mumbai",
  startTime: "2099-06-01T19:00:00",
  startInstant: "2099-06-01T13:30:00Z",
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
};
const seats = () =>
  [1, 2, 3, 4].map((n) => ({
    seatId: 8000 + n,
    section: "Main",
    row: "A",
    number: n,
    label: `Main · A${n}`,
    x: 80 + n * 30,
    y: 130,
    price: 500,
    currency: "INR",
    status: "FREE",
  }));
async function fixture(page) {
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.route("**/*", (r) =>
    new URL(r.request().url()).origin === base ? r.continue() : r.abort(),
  );
  await page.route("**/js/auth.js", (r) =>
    r.fulfill({ contentType: "text/javascript", body: auth }),
  );
  await page.route("**/organizer/config.js", (r) =>
    r.fulfill({
      contentType: "text/javascript",
      body: 'window.TICKIT_API_BASE="";',
    }),
  );
  page.on("dialog", (d) =>
    d.type() === "prompt" ? d.accept("Fixture event update") : d.accept(),
  );
  return errors;
}
async function customer(page, mode = "SEATED", limit = 3) {
  const errors = await fixture(page);
  let rows = seats(),
    available = 4;
  await page.route("**/api/**", (r) => {
    const p = new URL(r.request().url()).pathname;
    if (p === "/api/events")
      return r.fulfill({
        json: [
          { ...common, id: 901, admissionMode: mode, perOrderLimit: limit },
        ],
      });
    if (p === "/api/events/901")
      return r.fulfill({
        json: {
          ...common,
          id: 901,
          status: "PUBLISHED",
          admissionMode: mode,
          perOrderLimit: limit,
        },
      });
    if (p.endsWith("/seats"))
      return r.fulfill({ json: mode === "GENERAL" ? [] : rows });
    if (p.endsWith("/admission"))
      return r.fulfill({
        json: [
          {
            id: 11,
            name: "Floor",
            capacity: 4,
            available,
            tickets: [
              {
                id: 91,
                name: "Regular",
                price: 500,
                currency: "INR",
                available,
              },
            ],
          },
        ],
      });
    return r.fulfill({ status: 404, json: { error: "Unknown fixture route" } });
  });
  await page.goto("/#event/901");
  await page.getByRole("heading", { name: common.name, exact: true }).waitFor();
  return {
    errors,
    unavailableSeat: () => (rows[0].status = "HELD"),
    soldOut: () => (available = 0),
  };
}
async function organizer(page) {
  const errors = await fixture(page);
  let data = [],
    saved;
  await page.route("**/api/**", async (r) => {
    const p = new URL(r.request().url()).pathname,
      method = r.request().method();
    if (p.endsWith("/profile"))
      return r.fulfill({ json: { id: 101, displayName: "Fixture organizer" } });
    if (p === "/api/organizer/events" && method === "GET")
      return r.fulfill({ json: data });
    if (p === "/api/organizer/events" && method === "POST") {
      saved = r.request().postDataJSON();
      const d = { ...saved, id: 1, version: 0, status: "DRAFT" };
      data = [d];
      return r.fulfill({ json: d });
    }
    const id = Number(p.split("/").pop());
    if (id === 10) await new Promise((resolve) => setTimeout(resolve, 300));
    if ([10, 20].includes(id))
      return r.fulfill({
        json: {
          ...common,
          id,
          name: `Draft ${id}`,
          status: "DRAFT",
          venueName: "Hall",
          address: "Park Road",
          guestCapacity: 20,
          version: 0,
          seats: seats().map((s) => ({
            section: s.section,
            row: s.row,
            number: s.number,
            x: s.x,
            y: s.y,
            price: s.price,
          })),
          pools: [],
        },
      });
    return r.fulfill({ status: 404, json: { error: "Unknown fixture route" } });
  });
  await page.goto("/organizer/#new");
  await page.locator("[name=name]").waitFor();
  return { errors, saved: () => saved };
}

test("customer cap, deselection, totals, stale availability and focused age field", async ({
  page,
}) => {
  const f = await customer(page);
  for (let n = 1; n <= 4; n++)
    await page.locator(`[data-seat="${8000 + n}"]`).click();
  await expect(page.locator(".seat.selected")).toHaveCount(3);
  await expect(page.locator("#checkout .checkout-total")).toContainText(
    "1,500",
  );
  await page.locator('[data-seat="8002"]').click();
  await page.locator('[data-seat="8004"]').click();
  await expect(page.locator(".seat.selected")).toHaveCount(3);
  await page.locator("#attendeeAge").fill("21");
  await page.locator("#attendeeAge").focus();
  f.unavailableSeat();
  await page.evaluate(() => refreshSeats());
  await expect(page.locator(".seat.selected")).toHaveCount(2);
  await expect(page.locator("#attendeeAge")).toBeFocused();
  await expect(page.locator("#attendeeAge")).toHaveValue("21");
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth > innerWidth,
    ),
  ).toBe(false);
  expect(f.errors).toEqual([]);
});
test("lower event limit and category filter survive navigation", async ({
  page,
}) => {
  await customer(page, "SEATED", 2);
  for (let n = 1; n <= 3; n++)
    await page.locator(`[data-seat="${8000 + n}"]`).click();
  await expect(page.locator(".seat.selected")).toHaveCount(2);
  await page.goto("/");
  await page.locator('[data-category="community"]').click();
  await page.locator(".event-card a").first().click();
  await page.locator("[data-seat]").first().waitFor();
  await page.locator('a[href="#events"]').first().click();
  await expect(page.locator('[data-category="community"]')).toHaveClass(
    /active/,
  );
});
test("standing quantity is integral, clamped and zero when sold out", async ({
  page,
}) => {
  const f = await customer(page, "GENERAL");
  await page.locator("[data-admission-ticket]").click();
  await page.locator("#admissionQuantity").fill("99");
  await expect(page.locator("#admissionQuantity")).toHaveValue("3");
  await page.locator("#admissionQuantity").fill("2.7");
  await expect(page.locator("#admissionQuantity")).toHaveValue("2");
  await expect(page.locator("#admissionTotal")).toContainText("1,000");
  f.soldOut();
  await page.evaluate(() => refreshSeats());
  await expect(page.locator("#admissionQuantity")).toBeDisabled();
  await expect(page.locator("#admissionQuantity")).toHaveValue("0");
  await expect(page.locator("#admissionTotal")).toContainText("₹0");
  expect(f.errors).toEqual([]);
});
test("organizer selection, bulk price, preview immutability and CSV validation", async ({
  page,
}) => {
  const f = await organizer(page);
  await page.locator("#rowCount").fill("2");
  await page.locator("#columnCount").fill("4");
  await page.locator("[data-add-section]").click();
  await expect(page.locator("#layoutMap .map-seat")).toHaveCount(8);
  await page.locator('[data-tool="select"]').click();
  for (let n = 0; n < 4; n++)
    await page.locator("#layoutMap .map-seat").nth(n).click();
  await expect(page.locator('#layoutMap [aria-pressed="true"]')).toHaveCount(4);
  await page.locator("#selectedPrice").fill("750");
  await page.locator("[data-price-selected]").click();
  await expect(
    page.locator("#layoutMap title").filter({ hasText: "750" }),
  ).toHaveCount(4);
  const before = await page.locator("#layoutMap").innerHTML();
  await page.locator("[data-preview]").click();
  await page.locator("#preview .map-seat").first().focus();
  await page.keyboard.press("Enter");
  await expect(page.locator("#layoutMap")).toHaveJSProperty(
    "innerHTML",
    before,
  );
  await page.locator("[data-close-preview]").click();
  await page.locator("#seatCsv").setInputFiles({
    name: "bad.csv",
    mimeType: "text/csv",
    buffer: Buffer.from("wrong,header\n1,2"),
  });
  await expect(page.locator("#notice")).toContainText("CSV header");
  await expect(page.locator("#layoutMap .map-seat")).toHaveCount(8);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth > innerWidth,
    ),
  ).toBe(false);
  expect(f.errors).toEqual([]);
});
test("hidden admission fields do not block seated draft saving", async ({
  page,
}) => {
  const f = await organizer(page);
  await page.locator("[name=name]").fill("Private draft");
  await page.locator("[name=venueName]").fill("Hall");
  await page.locator("[name=city]").fill("Mumbai");
  await page.locator("[name=address]").fill("Park Road");
  await page.locator("[name=startTime]").fill("2099-06-01T19:00");
  await page.locator("[name=admissionMode]").selectOption("MIXED");
  await page.locator("[data-add-pool]").click();
  await page.locator("[name=admissionMode]").selectOption("SEATED");
  await page.locator("[name=minimumAge]").fill("18");
  await page.getByRole("button", { name: "Save draft", exact: true }).click();
  await expect.poll(() => f.saved()?.minimumAge).toBe(18);
  expect(f.saved().pools).toEqual([]);
  expect(f.errors).toEqual([]);
});
test("slow organizer navigation cannot replace the newer event", async ({
  page,
}) => {
  const f = await organizer(page);
  const request = page.waitForRequest("**/api/organizer/events/10");
  await page.evaluate(() => (location.hash = "event/10"));
  await request;
  await page.evaluate(() => (location.hash = "event/20"));
  await expect(page.locator("[name=name]")).toHaveValue("Draft 20");
  await page.waitForTimeout(400);
  await expect(page.locator("[name=name]")).toHaveValue("Draft 20");
  expect(f.errors).toEqual([]);
});
test("developer diagnostics display failures without fabricated success", async ({
  page,
}) => {
  const errors = await fixture(page);
  let requests = 0;
  await page.route("**/api/developer/**", (r) => {
    if (r.request().url().endsWith("/overview"))
      return r.fulfill({
        json: {
          catalog: { events: 1, seats: 4, bookings: 0, accounts: 1 },
          services: { postgresql: true, redis: true, objectStorage: true },
          integrations: { authentication: true, payments: false, email: false },
          checkedAt: "fixture",
        },
      });
    if (r.request().method() === "POST") {
      requests++;
      return r.fulfill({ status: 202, json: { status: "RUNNING" } });
    }
    return r.fulfill({
      json: requests
        ? {
            status: "FAILED",
            attempts: 16,
            concurrency: 4,
            durationMs: 50,
            cleanupComplete: true,
            checks: [
              {
                name: "Redis seat contention",
                passed: false,
                accepted: 0,
                rejected: 0,
                errors: 16,
                p95Ms: 5,
              },
            ],
          }
        : { status: "IDLE" },
    });
  });
  await page.goto("/developer/");
  await page.getByRole("heading", { name: "Workspace overview" }).waitFor();
  await page.locator('[data-section="testing"]').click();
  await page.getByRole("button", { name: "Run bot test" }).click();
  await expect(
    page.getByRole("heading", { name: "FAILED", exact: true }),
  ).toBeVisible();
  await expect(page.locator("#diagnosticResult")).toContainText("16 errors");
  expect(requests).toBe(1);
  expect(errors).toEqual([]);
});

test("late account response cannot restore a signed-out session", async ({
  page,
}) => {
  await page.route("**/*", (r) =>
    new URL(r.request().url()).origin === base ? r.continue() : r.abort(),
  );
  await page.route("**/js/app.js", (r) =>
    r.fulfill({ contentType: "text/javascript", body: "" }),
  );
  await page.route("**/api/public/config", (r) =>
    r.fulfill({
      json: {
        authProvider: "clerk",
        publishableKey:
          "pk_test_" + Buffer.from("issuer.test$").toString("base64"),
      },
    }),
  );
  await page.route("**/npm/@clerk/**", (r) =>
    r.fulfill({
      contentType: "text/javascript",
      body: "window.Clerk={session:null,load:async()=>{},addListener:fn=>window.authListener=fn};window.__internal_ClerkUICtor=function(){};",
    }),
  );
  await page.route("**/api/me", async (r) => {
    await new Promise((resolve) => setTimeout(resolve, 300));
    await r.fulfill({ json: { id: 101, name: "Old session fixture" } });
  });
  await page.goto("/");
  await page.evaluate(() => TickItAuth.init());
  const pending = page.waitForRequest("**/api/me");
  await page.evaluate(() => {
    Clerk.session = { getToken: async () => "fixture" };
    window.authListener();
  });
  await pending;
  await page.evaluate(() => {
    Clerk.session = null;
    window.authListener();
  });
  await page.waitForTimeout(400);
  expect(await page.evaluate(() => TickItAuth.user)).toBeNull();
});

test("saving an old organizer draft does not replace a newly opened event", async ({
  page,
}) => {
  await organizer(page);
  await page.route("**/api/organizer/events", async (r) => {
    if (r.request().method() !== "POST") return r.fallback();
    const data = r.request().postDataJSON();
    await new Promise((resolve) => setTimeout(resolve, 300));
    return r.fulfill({ json: { ...data, id: 1, version: 0, status: "DRAFT" } });
  });
  for (const [name, value] of Object.entries({
    name: "Old draft",
    venueName: "Hall",
    city: "Mumbai",
    address: "Park Road",
    startTime: "2099-06-01T19:00",
  }))
    await page.locator(`[name=${name}]`).fill(value);
  const request = page.waitForRequest(
    (r) => r.method() === "POST" && r.url().endsWith("/api/organizer/events"),
  );
  await page.getByRole("button", { name: "Save draft", exact: true }).click();
  await request;
  await page.evaluate(() => (location.hash = "event/20"));
  await expect(page.locator("[name=name]")).toHaveValue("Draft 20");
  await page.waitForTimeout(400);
  await expect(page.locator("[name=name]")).toHaveValue("Draft 20");
  await expect(page).toHaveURL(/#event\/20$/);
});

for (const area of ["customer", "organizer"]) {
  test(`${area} recovers from a non-JSON gateway failure`, async ({ page }) => {
    const errors = await fixture(page);
    let failures = 1;
    await page.route("**/api/**", (r) => {
      const path = new URL(r.request().url()).pathname;
      if (path === "/api/organizer/profile")
        return r.fulfill({
          json: { id: 101, displayName: "Fixture organizer" },
        });
      if (failures-- > 0)
        return r.fulfill({
          status: 503,
          contentType: "text/html",
          body: "<h1>Gateway unavailable</h1>",
        });
      return r.fulfill({ json: [] });
    });
    await page.goto(area === "customer" ? "/" : "/organizer/");
    await expect(
      page.getByText(
        "The service is temporarily unavailable. Please try again.",
        { exact: true },
      ),
    ).toBeVisible();
    await page.getByRole("button", { name: "Try again", exact: true }).click();
    if (area === "organizer")
      await expect(
        page.getByRole("heading", { name: "Your first event starts here." }),
      ).toBeVisible();
    else
      await expect(
        page.getByRole("heading", { name: "Worth being there for." }),
      ).toBeVisible();
    expect(errors).toEqual([]);
  });
}

async function paymentFixture(page, behavior = "success") {
  const f = await customer(page);
  await page.evaluate(() => {
    TickItAuth.config.checkoutEnabled = true;
  });
  const calls = [];
  const order = {
    id: "11111111-1111-4111-8111-111111111111",
    eventId: 901,
    status: "PENDING",
    amount: 100000,
    currency: "INR",
    mode: "test",
    keyId: "rzp_test_fixture",
    providerOrderId: "order_fixture",
    expiresAt: new Date(Date.now() + 115000).toISOString(),
    items: [
      { seatId: 8001, label: "Main · A1", quantity: 1, unitAmount: 50000 },
      { seatId: 8002, label: "Main · A2", quantity: 1, unitAmount: 50000 },
    ],
  };
  await page.route("**/api/payments/**", async (r) => {
    const path = new URL(r.request().url()).pathname,
      method = r.request().method();
    calls.push({ path, method, body: r.request().postDataJSON() });
    if (path.endsWith("/verify")) {
      if (behavior === "verify-error")
        return r.fulfill({
          status: 503,
          json: { error: "Verification temporarily unavailable" },
        });
      return r.fulfill({ json: { ...order, status: "CONFIRMED" } });
    }
    if (path.endsWith("/reconcile"))
      return r.fulfill({ json: { ...order, status: "CONFIRMED" } });
    if (method === "DELETE")
      return r.fulfill({ json: { ...order, status: "EXPIRED" } });
    return r.fulfill({ json: order });
  });
  await page.evaluate((behavior) => {
    window.Razorpay = class {
      constructor(options) {
        this.options = options;
        window.paymentOptions = options;
      }
      on() {}
      open() {
        if (behavior === "dismiss") {
          this.options.modal.ondismiss();
          return;
        }
        setTimeout(
          () =>
            this.options.handler({
              razorpay_order_id: "order_fixture",
              razorpay_payment_id: "pay_fixture",
              razorpay_signature: "a".repeat(64),
            }),
          10,
        );
      }
    };
  }, behavior);
  for (const id of [8001, 8002])
    await page.locator(`[data-seat="${id}"]`).click();
  await page.locator("#attendeeAge").fill("25");
  return { ...f, calls };
}

test("test checkout sends the complete selection and confirms only after server verification", async ({
  page,
}) => {
  const f = await paymentFixture(page);
  await page
    .getByRole("button", { name: "Continue to payment", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Booking confirmed", exact: true }),
  ).toBeVisible();
  expect(f.calls.find((c) => c.path.endsWith("/orders")).body.seatIds).toEqual([
    8001, 8002,
  ]);
  expect(f.calls.find((c) => c.path.endsWith("/orders")).body.attendeeAge).toBe(
    25,
  );
  expect(f.calls.some((c) => c.path.endsWith("/verify"))).toBe(true);
  expect(await page.evaluate(() => window.paymentOptions.order_id)).toBe(
    "order_fixture",
  );
  expect(f.errors).toEqual([]);
});

test("dismissing the provider window keeps a temporary order and allows explicit release", async ({
  page,
}) => {
  const f = await paymentFixture(page, "dismiss");
  await page
    .getByRole("button", { name: "Continue to payment", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Resume payment", exact: true }),
  ).toBeEnabled();
  expect(f.calls.some((c) => c.path.endsWith("/verify"))).toBe(false);
  await page
    .getByRole("button", { name: "Release reservation", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Continue to payment", exact: true }),
  ).toBeVisible();
  expect(f.calls.some((c) => c.method === "DELETE")).toBe(true);
  expect(f.errors).toEqual([]);
});

test("a failed callback verification can recover through payment status without another charge", async ({
  page,
}) => {
  const f = await paymentFixture(page, "verify-error");
  await page
    .getByRole("button", { name: "Continue to payment", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Check payment status", exact: true }),
  ).toBeEnabled();
  await page
    .getByRole("button", { name: "Check payment status", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Booking confirmed", exact: true }),
  ).toBeVisible();
  expect(f.calls.filter((c) => c.path.endsWith("/orders"))).toHaveLength(1);
  expect(f.calls.some((c) => c.path.endsWith("/reconcile"))).toBe(true);
  expect(f.errors).toEqual([]);
});

test("account history can recover a pending checkout without creating another order", async ({
  page,
}) => {
  const f = await paymentFixture(page, "dismiss");
  await page
    .getByRole("button", { name: "Continue to payment", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Resume payment", exact: true }),
  ).toBeEnabled();
  const order = {
    id: "11111111-1111-4111-8111-111111111111",
    eventId: 901,
    status: "PENDING",
    amount: 100000,
    currency: "INR",
    mode: "test",
    keyId: "rzp_test_fixture",
    providerOrderId: "order_fixture",
    expiresAt: new Date(Date.now() + 115000).toISOString(),
    items: [
      { seatId: 8001, label: "Main · A1", quantity: 1, unitAmount: 50000 },
    ],
  };
  await page.route("**/api/bookings/me", (r) => r.fulfill({ json: [] }));
  await page.route("**/api/payments/orders/me", (r) =>
    r.fulfill({ json: [order] }),
  );
  await page.evaluate(() => {
    location.hash = "#tickets";
  });
  await expect(
    page.getByRole("button", { name: "View checkout", exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "View checkout", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Resume payment", exact: true }),
  ).toBeEnabled();
  expect(f.calls.filter((c) => c.path.endsWith("/orders"))).toHaveLength(1);
  expect(f.errors).toEqual([]);
});

async function publishedOrganizer(page) {
  const errors = await fixture(page);
  let submitted,
    actions = [];
  let event = {
    ...common,
    id: 77,
    status: "PUBLISHED",
    version: 0,
    address: "12 Park Road",
    guestCapacity: 30,
    admissionMode: "SEATED",
    imageUrl: "",
    salesStart: null,
    salesEnd: null,
    endTime: null,
    pools: [],
    bookedSeatIds: [],
    seats: Array.from({ length: 8 }, (_, i) => ({
      id: 500 + i,
      section: "Main",
      row: "A",
      number: i + 1,
      x: 80 + i * 30,
      y: 160,
      price: 500,
    })),
  };
  await page.route("**/api/**", async (r) => {
    const path = new URL(r.request().url()).pathname,
      method = r.request().method();
    if (path.endsWith("/profile"))
      return r.fulfill({ json: { id: 101, displayName: "Fixture organizer" } });
    if (path === "/api/organizer/events")
      return r.fulfill({ json: event.status === "DELETED" ? [] : [event] });
    if (path.endsWith("/cancel")) {
      actions.push("cancel");
      event = { ...event, status: "CANCELLED", version: event.version + 1 };
      return r.fulfill({ json: event });
    }
    if (method === "DELETE") {
      actions.push("delete");
      event = { ...event, status: "DELETED" };
      return r.fulfill({ json: event });
    }
    if (method === "PUT") {
      submitted = r.request().postDataJSON();
      event = {
        ...event,
        ...submitted,
        status: "PUBLISHED",
        version: event.version + 1,
      };
      return r.fulfill({ json: event });
    }
    return r.fulfill({ json: event });
  });
  await page.goto("/organizer/#event/77");
  await expect(
    page.getByRole("button", { name: "Save changes", exact: true }),
  ).toBeVisible();
  return { errors, submitted: () => submitted, actions };
}

test("published organizer can select all eight seats, move them together and save shared prices", async ({
  page,
}) => {
  const f = await publishedOrganizer(page);
  await page
    .getByRole("button", { name: "Select all seats", exact: true })
    .click();
  await expect(page.locator('#layoutMap [aria-pressed="true"]')).toHaveCount(8);
  await page.locator("#selectedPrice").fill("925");
  await page.locator("[data-price-selected]").click();
  await expect(
    page.locator("#layoutMap title").filter({ hasText: "925" }),
  ).toHaveCount(8);
  await page.locator('[data-tool="move"]').click();
  const before = await page
    .locator("#layoutMap .map-seat rect")
    .first()
    .getAttribute("x");
  const box = await page
    .locator("#layoutMap .map-seat rect")
    .first()
    .boundingBox();
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  await page.mouse.move(
    box.x + box.width / 2 + 25,
    box.y + box.height / 2 + 25,
    { steps: 4 },
  );
  await page.mouse.up();
  await expect(
    page.locator("#layoutMap .map-seat rect").first(),
  ).not.toHaveAttribute("x", before);
  await page.getByRole("button", { name: "Save changes", exact: true }).click();
  await expect.poll(() => f.submitted()?.seats.length).toBe(8);
  expect(
    f.submitted().seats.every((s) => s.price === 925 && s.id != null),
  ).toBe(true);
  expect(
    new Set(f.submitted().seats.map((s) => s.x - (80 + (s.number - 1) * 30)))
      .size,
  ).toBe(1);
  expect(f.errors).toEqual([]);
});

test("organizer bulk removal and published schedule change reach the save API", async ({
  page,
}) => {
  const f = await publishedOrganizer(page);
  await page.locator('[data-tool="select"]').click();
  for (let i = 0; i < 4; i++)
    await page.locator("#layoutMap .map-seat").nth(i).click();
  await page.locator("[data-remove-selected]").click();
  await expect(page.locator("#layoutMap .map-seat")).toHaveCount(4);
  await page.locator('[name="startTime"]').fill("2099-05-01T19:00");
  await page.locator('[name="changeReason"]').fill("Earlier venue booking");
  await page.getByRole("button", { name: "Save changes", exact: true }).click();
  await expect.poll(() => f.submitted()?.seats.length).toBe(4);
  expect(f.submitted().changeReason).toBe("Earlier venue booking");
  expect(f.submitted().startTime).toBe("2099-05-01T19:00");
  expect(f.errors).toEqual([]);
});

test("organizer can cancel an event and then delete it from the workspace", async ({
  page,
}) => {
  const f = await publishedOrganizer(page);
  await page.getByRole("button", { name: "Cancel event", exact: true }).click();
  await expect(page.locator(".page-head .badge")).toHaveText("CANCELLED");
  await expect(page.locator('[name="name"]')).toBeDisabled();
  await page.getByRole("button", { name: "Delete event", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Your first event starts here." }),
  ).toBeVisible();
  expect(f.actions).toEqual(["cancel", "delete"]);
  expect(f.errors).toEqual([]);
});

test("cancelled paid event remains visible in My tickets after removal from public catalog", async ({
  page,
}) => {
  const errors = await fixture(page);
  await page.route("**/api/**", (r) => {
    const path = new URL(r.request().url()).pathname;
    if (path === "/api/events" || path === "/api/bookings/me")
      return r.fulfill({ json: [] });
    if (path === "/api/payments/orders/me")
      return r.fulfill({
        json: [
          {
            id: "11111111-1111-4111-8111-111111111111",
            eventId: 901,
            status: "CONFIRMED",
            amount: 50000,
            currency: "INR",
            event: {
              ...common,
              id: 901,
              status: "CANCELLED",
              changeNote: "Venue unavailable",
            },
            items: [
              {
                seatId: 8001,
                label: "Main · A1",
                quantity: 1,
                unitAmount: 50000,
              },
            ],
          },
        ],
      });
    return r.fulfill({ status: 404, json: { error: "Unknown fixture route" } });
  });
  await page.goto("/#tickets");
  await expect(page.locator(".ticket-card")).toHaveCount(1);
  await expect(page.locator(".ticket-card .status-badge")).toHaveText(
    "CANCELLED · REFUND PENDING",
  );
  await expect(page.locator(".ticket-card")).toContainText("Venue unavailable");
  expect(errors).toEqual([]);
});

for (const [refundStatus, label] of [
  ["PROCESSED", "REFUND PROCESSED"],
  ["FAILED", "REFUND NEEDS ATTENTION"],
  ["RETRY", "REFUND QUEUED"],
]) {
  test(`cancelled ticket shows authoritative ${refundStatus} refund state`, async ({
    page,
  }) => {
    const errors = await fixture(page);
    await page.route("**/api/**", (r) => {
      const path = new URL(r.request().url()).pathname;
      if (path === "/api/events" || path === "/api/bookings/me")
        return r.fulfill({ json: [] });
      if (path === "/api/payments/orders/me")
        return r.fulfill({
          json: [
            {
              id: "11111111-1111-4111-8111-111111111111",
              eventId: 901,
              status: "CONFIRMED",
              amount: 50000,
              currency: "INR",
              event: {
                ...common,
                id: 901,
                status: "CANCELLED",
                changeNote: "Venue unavailable",
              },
              refund: { status: refundStatus, amount: 50000, currency: "INR" },
              items: [
                {
                  seatId: 8001,
                  label: "Main · A1",
                  quantity: 1,
                  unitAmount: 50000,
                },
              ],
            },
          ],
        });
      return r.fulfill({
        status: 404,
        json: { error: "Unknown fixture route" },
      });
    });
    await page.goto("/#tickets");
    await expect(page.locator(".ticket-card .status-badge")).toHaveText(label);
    expect(errors).toEqual([]);
  });
}

test("developer payment operations display failed refunds for follow-up", async ({
  page,
}) => {
  const errors = await fixture(page);
  await page.route("**/api/developer/**", (r) => {
    if (r.request().url().endsWith("/overview"))
      return r.fulfill({
        json: {
          catalog: { events: 1, seats: 1, bookings: 1, accounts: 1 },
          services: { postgresql: true, redis: true, objectStorage: true },
          integrations: { authentication: true, payments: true, email: false },
          checkedAt: "fixture",
        },
      });
    return r.fulfill({
      json: [
        {
          order_id: "11111111-1111-4111-8111-111111111111",
          status: "FAILED",
          reason: "EVENT_CANCELLED",
          amount: 50000,
          currency: "INR",
          last_error: "Provider refund requires follow-up",
        },
      ],
    });
  });
  await page.goto("/developer/");
  await page.getByRole("heading", { name: "Workspace overview" }).waitFor();
  await page.locator('[data-section="payments"]').click();
  await expect(page.locator("#refundList")).toContainText("FAILED");
  await expect(page.locator("#refundList")).toContainText(
    "Provider refund requires follow-up",
  );
  expect(errors).toEqual([]);
});

test("legacy event links become readable paths and survive reload and browser navigation", async ({
  page,
}) => {
  const f = await customer(page);
  await expect(page).toHaveURL(/\/events\/independent-sessions-mumbai$/);
  await page.reload();
  await expect(
    page.getByRole("heading", { name: common.name, exact: true }),
  ).toBeVisible();
  await expect(page).toHaveTitle(`${common.name} | TickIt`);
  await page.getByRole("link", { name: "Back to experiences" }).click();
  await page.getByRole("link", { name: "Find tickets", exact: true }).click();
  await expect(page).toHaveURL(/\/events\/independent-sessions-mumbai$/);
  await page.goBack();
  await expect(page.locator("#eventsGrid")).toBeVisible();
  await page.goForward();
  await expect(
    page.getByRole("heading", { name: common.name, exact: true }),
  ).toBeVisible();
  expect(f.errors).toEqual([]);
});
test("unknown slug navigation shows an honest unavailable state", async ({
  page,
}) => {
  const f = await customer(page);
  await page.evaluate(() => {
    history.pushState(null, "", "/events/unlisted-experience");
    dispatchEvent(new PopStateEvent("popstate"));
  });
  await expect(page.locator(".page-error")).toContainText("could not be found");
  expect(f.errors).toEqual([]);
});
test("organizer customer link uses the published event slug", async ({
  page,
}) => {
  const f = await publishedOrganizer(page);
  await expect(
    page.getByRole("link", { name: "View customer page" }),
  ).toHaveAttribute("href", "/events/independent-sessions-mumbai");
  expect(f.errors).toEqual([]);
});
test("removed event checkout history remains readable without a public event page", async ({
  page,
}) => {
  const errors = await fixture(page);
  const order = {
    id: "11111111-1111-4111-8111-111111111111",
    eventId: 901,
    status: "REVIEW_REQUIRED",
    amount: 50000,
    currency: "INR",
    event: { ...common, id: 901, status: "CANCELLED" },
    refund: { status: "PROCESSED" },
    items: [
      { seatId: 8001, label: "Main · A1", quantity: 1, unitAmount: 50000 },
    ],
  };
  await page.route("**/api/**", (r) => {
    const path = new URL(r.request().url()).pathname;
    if (path === "/api/events" || path === "/api/bookings/me")
      return r.fulfill({ json: [] });
    if (path === "/api/payments/orders/me") return r.fulfill({ json: [order] });
    if (path === `/api/payments/orders/${order.id}`)
      return r.fulfill({ json: order });
    return r.fulfill({ status: 404, json: { error: "Unavailable" } });
  });
  await page.goto("/#tickets");
  await page.getByRole("button", { name: "View checkout" }).click();
  await expect(page.locator("#checkout")).toContainText("REFUND PROCESSED");
  expect(errors).toEqual([]);
});
