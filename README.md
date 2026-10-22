# ⚡ TickIt — High-Throughput Real-Time Ticket Reservation Engine

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue.svg)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/Redis-7%2F8-red.svg)](https://redis.io/)
[![Build Status](https://img.shields.io/badge/Tests-11%20Passed%20%2F%200%20Failed-success.svg)]()

> **TickIt** is a high-concurrency ticket booking system engineered to eliminate double-booking during high-traffic flash sales, defend the booking pipeline against abusive automated bot spikes, and broadcast live seat availability across connected clients in real time.

---

## 📑 Table of Contents
1. [Core Architectural Mechanisms](#-core-architectural-mechanisms)
2. [System Architecture & Data Flow](#-system-architecture--data-flow)
3. [Backend Testing & Concurrency Verification](#-backend-testing--concurrency-verification)
4. [Live Flash-Sale Benchmark CLI](#-live-flash-sale-benchmark-cli)
5. [REST API Specifications](#-rest-api-specifications)
6. [Getting Started](#-getting-started)
7. [Tech Stack](#-tech-stack)

---

## 🛡️ Core Architectural Mechanisms

### 1. Two-Tier Concurrency Control (Zero Double-Booking)
To eliminate duplicate seat bookings during extreme flash-sale traffic spikes, TickIt employs a complementary two-tier concurrency strategy:
* **Tier 1 (Fast Distributed Lock - Redis `SETNX`)**: When a user selects a seat, TickIt attempts to acquire an atomic distributed hold in Redis using `SETNX` with a 120-second TTL:
  ```text
  KEY: hold:{eventId}:{seatId} -> VALUE: {userId} (TTL: 120s)
  ```
  If another user or thread tries to claim the same seat, Redis atomically rejects the request with `HTTP 409 Conflict`. If the user abandons checkout, the hold automatically expires after 120 seconds, returning the seat to the public pool without database overhead.
* **Tier 2 (Source of Truth - PostgreSQL Optimistic Concurrency Control)**: The database enforces absolute integrity using JPA `@Version` column and a hard relational constraint:
  ```sql
  UNIQUE (seat_id, event_id)
  ```
  Even if the distributed lock layer were to experience network partition or failover, PostgreSQL's MVCC and OCC guarantee that exactly **one** booking transaction can commit; any competing transaction fails immediately and rolls back.

### 2. Bot-Resistant Token-Bucket Rate Limiter (Redis Lua)
To protect checkout endpoints from high-velocity bot attacks, TickIt executes an atomic token-bucket script in Redis before request processing:
* Evaluated per IP (`rl:ip:{address}`) and per User (`rl:user:{id}`).
* Configured with a burst capacity of 20 tokens and continuous refill (5 tokens/sec).
* Evaluated atomically in single-threaded Redis via [`rate_limiter.lua`](src/main/resources/scripts/rate_limiter.lua).
* Requests exceeding the bucket limit are immediately rejected with **`HTTP 429 Too Many Requests`** and include a `Retry-After: 10` header, shielding downstream database connection pools.

### 3. Real-Time Push Over WebSocket (STOMP)
Instead of forcing clients to poll continuously, TickIt maintains bi-directional WebSocket connections over STOMP:
* Clients subscribe to `/topic/seats/{eventId}`.
* Status transitions (`FREE` ➔ `HELD` ➔ `BOOKED`) are broadcast instantly when holds or bookings occur.

---

## 🏗️ System Architecture & Data Flow

```text
                             ┌──────────────────────┐
                             │    Client Browser    │
                             │ (REST + STOMP / WS)  │
                             └──────────┬───────────┘
                                        │
                        1. POST /holds  │     ▲ 5. STOMP Broadcast
                        2. POST /books  │     │    (/topic/seats/{id})
                                        ▼     │
                             ┌──────────────────────┐
                             │  Spring Boot Server  │
                             │ RateLimiterFilter    │
                             └──────────┬───────────┘
                                        │
                      1. Check Tokens   ▼   2. Acquire Hold
                         ┌───────────────────────────────┐
                         │          Redis v7/v8          │
                         │  - Token Bucket (Lua script)  │
                         │  - Hold Keys (120s TTL)       │
                         └──────────────┬────────────────┘
                                        │
                                        ▼ 3. Commit Booking
                         ┌───────────────────────────────┐
                         │       PostgreSQL 16/18        │
                         │  - JPA @Version (OCC)         │
                         │  - UNIQUE (seat_id, event_id) │
                         └───────────────────────────────┘
```

---

## 🧪 Backend Testing & Concurrency Verification

TickIt includes an automated testing suite validating concurrency invariants, rate limiting, and end-to-end API flows.

### Running the Test Suite
```bash
mvn test
```

### Key Test Coverage

#### 1. Multithreaded Concurrency Stress Test (`BookingConcurrencyTest.java`)
Launches **50 concurrent threads** aligned via `CountDownLatch` that all fire simultaneously at the exact same millisecond against a single contested seat.
* **Test Invariants Verified**:
  * **Invariant 1**: Exactly 1 thread successfully acquires the hold and confirms the booking.
  * **Invariant 2**: All other 49 threads receive conflict rejections.
  * **Invariant 3 (Database Integrity)**: Database verification query asserts that `COUNT(*)` in the `booking` table for that seat equals **exactly 1**.

```text
==================================================
CONCURRENCY TEST RESULTS:
Total Competing Threads: 50
Successful Bookings:     1
Rejected Attempts:       49
==================================================
[INFO] Tests run: 1, Failures: 0, Errors: 0, Time elapsed: 0.183 s -- in com.tickit.BookingConcurrencyTest
```

#### 2. Token-Bucket Rate Limiter Tests (`RateLimiterServiceTest.java`)
* Verifies 20 burst requests are accepted within quota.
* Verifies the 21st request receives `HTTP 429 Too Many Requests`.
* Verifies token bucket refills based on elapsed time.

#### 3. Seat Hold Lifecycle Tests (`SeatHoldServiceTest.java`)
* Verifies atomic acquisition by the first user and rejection of competing users.
* Verifies idempotent behavior for re-entrant requests from the same user.
* Verifies release mechanism restores seat availability.

#### 4. REST API Integration Tests (`BookingFlowIntegrationTest.java`)
* MockMvc validation of event listings, seat status queries, and conflict exception handling (`409 Conflict`, `410 Gone`).

---

## ⚡ Live Flash-Sale Benchmark CLI

A standalone bash benchmark script is included to simulate real-world traffic spikes against the running backend:

```bash
./scripts/concurrency_benchmark.sh http://localhost:8080 1 50
```

### Benchmark Output:
```text
==================================================================
⚡ TICKIT FLASH-SALE CONCURRENCY BENCHMARK
Target Base URL: http://localhost:8080
Target Event ID: 1
Concurrent Spikes: 50 requests
==================================================================
🔍 Querying seat map for a FREE seat... Targeting Seat: ID 4 (A4)
------------------------------------------------------------------
🚀 Dispatching 50 concurrent requests to hold Seat A4 simultaneously...
==================================================================
📊 BENCHMARK RESULTS SUMMARY:
------------------------------------------------------------------
Total Concurrent Requests        : 50
200 OK (Hold Acquired)           : 1
409 CONFLICT (Blocked by Redis)  : 19
429 RATE-LIMITED (Blocked by Lua): 30
==================================================================
✅ PASSED: Exactly 1 user acquired the hold. Zero race-condition overlap!
💳 Confirming booking for the winning user... SUCCESS! Booking ID #4 confirmed.
Seat A4 Final Live Status: BOOKED
==================================================================
```

---

## 📡 REST API Specifications

### Endpoints Overview

| Method | Endpoint | Description | Status Codes |
|---|---|---|---|
| `GET` | `/api/events` | List all events with venue info and live availability | `200 OK` |
| `GET` | `/api/events/{id}` | Get specific event details | `200 OK`, `404 Not Found` |
| `GET` | `/api/events/{id}/seats` | Real-time seat grid with statuses (`FREE`, `HELD`, `BOOKED`) | `200 OK` |
| `POST` | `/api/holds` | Acquire short-TTL hold on a seat | `200 OK`, `409 Conflict`, `429 Too Many Requests` |
| `DELETE`| `/api/holds/{eventId}/{seatId}` | Voluntarily release a hold | `200 OK` |
| `POST` | `/api/bookings` | Finalize booking (OCC committed in database) | `201 Created`, `409 Conflict`, `410 Gone`, `429 Too Many Requests` |
| `GET` | `/api/bookings/user/{userId}` | Retrieve booking history for a user | `200 OK` |

### Sample Payload: Hold Request
`POST /api/holds`
```json
{
  "eventId": 1,
  "seatId": 4,
  "userId": 1
}
```
**Response (`200 OK`)**:
```json
{
  "status": "HELD",
  "eventId": 1,
  "seatId": 4,
  "seatLabel": "A4",
  "expiresInSeconds": 120,
  "message": "Seat held successfully for 120 seconds. Complete booking before timer expires."
}
```

### Sample Payload: Confirm Booking
`POST /api/bookings`
```json
{
  "eventId": 1,
  "seatId": 4,
  "userId": 1
}
```
**Response (`201 Created`)**:
```json
{
  "bookingId": 4,
  "eventId": 1,
  "seatId": 4,
  "seatLabel": "A4",
  "status": "CONFIRMED",
  "bookedAt": "2026-10-22T17:21:46.418723"
}
```

---

## 🚀 Getting Started

### Prerequisites
* Java 21+ & Maven 3.9+
* PostgreSQL & Redis (running locally or via Docker)

### Option 1: 1-Click Launch with Docker Compose
```bash
git clone git@github.com:naman771/TickIt.git
cd TickIt

# Starts PostgreSQL, Redis, and TickIt
docker-compose up --build
```
Access the application at: **`http://localhost:8080`**

### Option 2: Native Run (with local PostgreSQL & Redis)
```bash
# 1. Create the database
createdb tickit

# 2. Start Redis
brew services start redis

# 3. Launch TickIt
mvn spring-boot:run
```

---

## 🛠️ Tech Stack
* **Language & Framework**: Java 21, Spring Boot 3.2.5
* **Data Layer**: Spring Data JPA, PostgreSQL 16/18, Flyway Migrations
* **Cache & Distributed Locking**: Redis 7/8, Redis Lua Scripts
* **Messaging & Real-Time**: Spring WebSocket, STOMP, SockJS
* **Testing**: JUnit 5, MockMvc, Java Concurrency Utilities (`CountDownLatch`, `ExecutorService`)
* **Deployment**: Docker, Docker Compose
