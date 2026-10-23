package com.tickit.payment;

import com.tickit.model.*;
import com.tickit.repository.*;
import com.tickit.service.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Slf4j
public class CheckoutService {
  private final PaymentSettings settings;
  private final EventSnapshots eventSnapshots;
  private final CheckoutStore store;
  private final org.springframework.jdbc.core.JdbcTemplate jdbc;
  private final RazorpayGateway gateway;
  private final CurrentUserService current;
  private final EventRepository events;
  private final SeatRepository seats;
  private final BookingRepository bookings;
  private final AdmissionTicketTypeRepository ticketTypes;
  private final AdmissionHoldRepository admissionHolds;
  private final SeatHoldService holds;
  private final TransactionTemplate tx;
  private final SimpMessagingTemplate messages;

  public void requireEnabled() {
    if (!settings.isEnabled()) throw error(503, "Online checkout is not yet available");
  }

  private ResponseStatusException error(int code, String message) {
    return new ResponseStatusException(HttpStatus.valueOf(code), message);
  }

  private CheckoutStore.Order required(UUID id) {
    var order = store.get(id);
    if (order == null) throw error(404, "Order not found");
    return order;
  }

  private CheckoutStore.Order owned(UUID id, long user) {
    var order = required(id);
    if (order.userId() != user) throw error(404, "Order not found");
    return order;
  }

  private void lock(long eventId) {
    events.lockById(eventId).orElseThrow(() -> error(404, "Event not found"));
  }

  public Map<String, Object> create(CheckoutRequest request) {
    requireEnabled();
    var user = current.resolve(null);
    boolean[] created = {false};
    var order =
        tx.execute(
            status -> {
              lock(request.eventId());
              var previous = store.forKey(user.getId(), request.idempotencyKey());
              if (previous != null) {
                if (!previous.fingerprint().equals(request.fingerprint()))
                  throw error(409, "This checkout key belongs to another selection");
                return previous;
              }
              var event = events.findById(request.eventId()).orElseThrow();
              if (!"PUBLISHED".equals(event.getPublicationStatus()))
                throw error(404, "Event not found");
              if (!Objects.equals(request.eventVersion(), event.getLayoutVersion()))
                throw error(409, "Event details changed. Refresh before checkout");
              EventSales.requireOpen(event);
              EventSales.requireAge(event, request.attendeeAge());
              int quantity = request.seatIds().size() + request.quantity();
              if (quantity < 1
                  || quantity > Math.min(3, event.getPerOrderLimit())
                  || new HashSet<>(request.seatIds()).size() != request.seatIds().size()
                  || (request.ticketTypeId() == null) != (request.quantity() == 0))
                throw error(400, "Choose one to three valid tickets");
              if (!"INR".equals(event.getCurrency()))
                throw error(400, "Online checkout currently supports INR events only");
              Integer active =
                  jdbc.queryForObject(
                      "SELECT count(*) FROM checkout_order WHERE user_id=? AND event_id=? AND"
                          + " status IN ('CREATING','CREATE_UNKNOWN','PENDING') AND"
                          + " expires_at>clock_timestamp()",
                      Integer.class,
                      user.getId(),
                      event.getId());
              if (active != null && active > 0)
                throw error(409, "Resume your existing checkout before starting another");
              var items = new ArrayList<CheckoutStore.Item>();
              var newlyHeld = new ArrayList<Long>();
              UUID id = UUID.randomUUID();
              // Redis cannot join the SQL transaction: compensate only this attempt's new holds on
              // rollback.
              TransactionSynchronizationManager.registerSynchronization(
                  new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int completion) {
                      if (completion != STATUS_COMMITTED)
                        for (Long seat : newlyHeld) {
                          try {
                            holds.releaseCheckout(event.getId(), seat, user.getId(), id);
                          } catch (Exception ex) {
                            log.warn("Checkout hold compensation will rely on TTL");
                          }
                        }
                    }
                  });
              Instant now = store.now(), deadline = now.plusSeconds(120);
              long confirmed = bookings.countAllTickets(user.getId(), event.getId());
              long activeAdmission = admissionHolds.activeForUser(user.getId(), event.getId());
              if (confirmed
                      + activeAdmission
                      + holds.activeForUser(event.getId(), user.getId())
                      + request.quantity()
                  > Math.min(3, event.getPerOrderLimit()))
                throw error(409, "Your per-event reservation limit has been reached");
              for (Long seatId : request.seatIds().stream().sorted().toList()) {
                var seat = seats.findById(seatId).orElseThrow(() -> error(400, "Seat not found"));
                if (seat.isRetired()) throw error(409, "Seat is no longer available");
                if (!seat.getVenue().getEvent().getId().equals(event.getId()))
                  throw error(400, "Seat does not belong to this event");
                if (bookings.existsBySeatIdAndEventId(seatId, event.getId()))
                  throw error(409, "Seat already booked");
                boolean owned = holds.isHoldValid(event.getId(), seatId, user.getId());
                if (!holds.acquireHold(
                    event.getId(),
                    seatId,
                    user.getId(),
                    (int)
                        (Math.min(3, event.getPerOrderLimit())
                            - confirmed
                            - activeAdmission
                            - request.quantity())))
                  throw error(409, "Seat unavailable or your reservation limit has been reached");
                if (!owned) newlyHeld.add(seatId);
                if (!holds.bindCheckout(event.getId(), seatId, user.getId(), id))
                  throw error(409, "Seat reservation expired");
                // Redis rounds TTL up for display. Subtract a second to avoid offering checkout
                // beyond its hold.
                deadline =
                    deadline.isBefore(
                            now.plusSeconds(
                                Math.max(0, holds.remainingSeconds(event.getId(), seatId) - 1)))
                        ? deadline
                        : now.plusSeconds(
                            Math.max(0, holds.remainingSeconds(event.getId(), seatId) - 1));
                items.add(
                    new CheckoutStore.Item(
                        seatId, null, seat.displayLabel(), 1, minor(seat.getPrice())));
              }
              UUID admissionId = null;
              if (request.ticketTypeId() != null) {
                var type =
                    ticketTypes
                        .findById(request.ticketTypeId())
                        .orElseThrow(() -> error(400, "Ticket type not found"));
                var pool = type.getPool();
                if (!pool.getEvent().getId().equals(event.getId()))
                  throw error(400, "Ticket does not belong to this event");
                if (pool.getCapacity() - pool.getSold() - admissionHolds.activeInPool(pool.getId())
                        < request.quantity()
                    || type.getQuota()
                            - type.getSold()
                            - admissionHolds.activeForTicket(type.getId())
                        < request.quantity())
                  throw error(409, "That ticket quantity is no longer available");
                admissionId = UUID.randomUUID();
                jdbc.update(
                    "INSERT INTO"
                        + " admission_hold(id,event_id,ticket_type_id,user_id,quantity,expires_at)"
                        + " VALUES(?,?,?,?,?,?)",
                    admissionId,
                    event.getId(),
                    type.getId(),
                    user.getId(),
                    request.quantity(),
                    java.sql.Timestamp.from(deadline));
                items.add(
                    new CheckoutStore.Item(
                        null,
                        type.getId(),
                        pool.getName() + " · " + type.getName(),
                        request.quantity(),
                        minor(type.getPrice())));
              }
              long amount =
                  items.stream()
                      .mapToLong(i -> Math.multiplyExact(i.unitAmount(), i.quantity()))
                      .reduce(0, Math::addExact);
              if (amount < 100 || amount > 100_000_000L)
                throw error(400, "Checkout total must be between ₹1 and ₹10,00,000");
              if (deadline.isBefore(now.plusSeconds(15)))
                throw error(
                    409, "Your reservation is about to expire. Release it and choose again");
              jdbc.update(
                  "INSERT INTO"
                      + " checkout_order(id,user_id,event_id,idempotency_key,request_fingerprint,status,amount,currency,admission_hold_id,expires_at,event_revision)"
                      + " VALUES(?,?,?,?,?,'CREATING',?,'INR',?,?,?)",
                  id,
                  user.getId(),
                  event.getId(),
                  request.idempotencyKey(),
                  request.fingerprint(),
                  amount,
                  admissionId,
                  java.sql.Timestamp.from(deadline),
                  event.getLayoutVersion());
              for (var item : items)
                jdbc.update(
                    "INSERT INTO"
                        + " checkout_item(order_id,seat_id,ticket_type_id,label,quantity,unit_amount)"
                        + " VALUES(?,?,?,?,?,?)",
                    id,
                    item.seatId(),
                    item.ticketTypeId(),
                    item.label(),
                    item.quantity(),
                    item.unitAmount());
              created[0] = true;
              return required(id);
            });
    if (created[0]) {
      try {
        attach(order.id(), gateway.create(order.id(), order.amount(), order.currency()));
      } catch (Exception ex) {
        tx.executeWithoutResult(
            s -> {
              lock(order.eventId());
              jdbc.update(
                  "UPDATE checkout_order SET status='CREATE_UNKNOWN' WHERE id=? AND"
                      + " status='CREATING'",
                  order.id());
            });
        throw new CheckoutSetupException(order.id());
      }
    }
    return view(required(order.id()));
  }

  private void attach(UUID id, RazorpayGateway.Order provider) {
    tx.executeWithoutResult(
        s -> {
          var before = required(id);
          lock(before.eventId());
          var order = required(id);
          if (!id.toString().equals(provider.receipt())
              || provider.amount() != order.amount()
              || !order.currency().equals(provider.currency())
              || provider.id() == null
              || !provider.id().matches("order_[A-Za-z0-9]{1,64}"))
            throw error(409, "Payment order did not match checkout");
          if (order.providerOrderId() != null && !order.providerOrderId().equals(provider.id()))
            throw error(409, "Payment order conflict");
          jdbc.update(
              "UPDATE checkout_order SET provider_order_id=?, status=CASE WHEN status IN"
                  + " ('CREATING','CREATE_UNKNOWN') THEN 'PENDING' ELSE status END WHERE id=?",
              provider.id(),
              id);
        });
  }

  public Map<String, Object> get(UUID id) {
    return view(owned(id, current.resolve(null).getId()));
  }

  public List<Map<String, Object>> history() {
    return store.history(current.resolve(null).getId()).stream().map(this::view).toList();
  }

  public Map<String, Object> verify(
      UUID id, String paymentId, String providerId, String signature) {
    requireEnabled();
    var order = owned(id, current.resolve(null).getId());
    if (order.providerOrderId() == null
        || !order.providerOrderId().equals(providerId)
        || !PaymentSignatures.valid(
            (order.providerOrderId() + "|" + paymentId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8),
            signature,
            settings.getKeySecret())) throw error(400, "Invalid payment signature");
    settle(id, gateway.payment(paymentId));
    return view(required(id));
  }

  public Map<String, Object> cancel(UUID id) {
    var order = owned(id, current.resolve(null).getId());
    tx.executeWithoutResult(
        s -> {
          lock(order.eventId());
          var latest = required(id);
          if (Set.of("CONFIRMED", "REVIEW_REQUIRED").contains(latest.status())) return;
          store.status(id, "EXPIRED", null);
          afterCommitRelease(latest, store.items(id), false);
        });
    return view(required(id));
  }

  public Map<String, Object> reconcile(UUID id) {
    requireEnabled();
    var order = owned(id, current.resolve(null).getId());
    reconcileOrder(order);
    return view(required(id));
  }

  void reconcileOrder(CheckoutStore.Order order) {
    if (order.providerOrderId() == null
        || Set.of("CONFIRMED", "REVIEW_REQUIRED").contains(order.status())) return;
    for (var payment : gateway.payments(order.providerOrderId()))
      if ("captured".equals(payment.status())) {
        settle(order.id(), payment);
        return;
      }
  }

  public void webhook(byte[] body, String signature) {
    requireEnabled();
    if (!PaymentSignatures.valid(body, signature, settings.getWebhookSecret()))
      throw error(401, "Invalid webhook signature");
    tools.jackson.databind.JsonNode message;
    try {
      message = new tools.jackson.databind.ObjectMapper().readTree(body);
    } catch (Exception ex) {
      throw error(400, "Invalid webhook payload");
    }
    String event = message.path("event").asText();
    if (!Set.of("payment.captured", "order.paid").contains(event)) return;
    String paymentId = message.path("payload").path("payment").path("entity").path("id").asText();
    if (!paymentId.matches("pay_[A-Za-z0-9]{1,64}")) throw error(400, "Missing payment reference");
    var payment = gateway.payment(paymentId);
    var provider = gateway.order(payment.orderId());
    UUID id;
    try {
      id = UUID.fromString(provider.receipt());
    } catch (Exception ex) {
      return;
    }
    if (store.get(id) == null) return; // Another application sharing this merchant account.
    attach(id, provider);
    settle(id, payment);
  }

  void settle(UUID id, RazorpayGateway.Payment payment) {
    tx.executeWithoutResult(
        s -> {
          var before = required(id);
          lock(before.eventId());
          var order = required(id);
          if (!Objects.equals(order.providerOrderId(), payment.orderId())
              || payment.amount() != order.amount()
              || !order.currency().equals(payment.currency())
              || !payment.id().matches("pay_[A-Za-z0-9]{1,64}"))
            throw error(409, "Payment details did not match checkout");
          if (!"captured".equals(payment.status()))
            return; // Authorization alone is not a completed payment.
          if (Set.of("CONFIRMED", "REVIEW_REQUIRED").contains(order.status())) {
            if (!payment.id().equals(order.paymentId()))
              throw error(409, "Payment reference conflict");
            return;
          }
          var items = store.items(id);
          Instant now = store.now();
          boolean available = order.expiresAt().isAfter(now) && !"EXPIRED".equals(order.status());
          long quantity = items.stream().mapToLong(CheckoutStore.Item::quantity).sum();
          var event = events.findById(order.eventId()).orElseThrow();
          available &=
              "PUBLISHED".equals(event.getPublicationStatus())
                  && order.eventRevision() == event.getLayoutVersion();
          available &=
              bookings.countAllTickets(order.userId(), order.eventId()) + quantity
                  <= Math.min(3, event.getPerOrderLimit());
          for (var item : items) {
            if (item.seatId() != null)
              available &=
                  !seats.findById(item.seatId()).orElseThrow().isRetired()
                      && !bookings.existsBySeatIdAndEventId(item.seatId(), order.eventId())
                      && holds.isCheckoutHoldValid(
                          order.eventId(), item.seatId(), order.userId(), order.id());
            else {
              var hold = admissionHolds.findById(order.admissionHoldId()).orElse(null);
              var type = ticketTypes.findById(item.ticketTypeId()).orElseThrow();
              available &=
                  hold != null
                      && !hold.isReleased()
                      && hold.getExpiresAt().isAfter(now)
                      && hold.getUser().getId().equals(order.userId())
                      && hold.getQuantity() == item.quantity()
                      && type.getQuota() - type.getSold() >= item.quantity()
                      && type.getPool().getCapacity() - type.getPool().getSold() >= item.quantity();
            }
          }
          if (!available) {
            store.status(id, "REVIEW_REQUIRED", payment.id());
            afterCommitRelease(order, items, false);
            return;
          }
          for (var item : items) {
            if (item.seatId() != null)
              jdbc.update(
                  "INSERT INTO booking(seat_id,event_id,user_id,status) VALUES(?,?,?,'CONFIRMED')",
                  item.seatId(),
                  order.eventId(),
                  order.userId());
            else {
              jdbc.update(
                  "UPDATE admission_ticket_type SET sold=sold+? WHERE id=?",
                  item.quantity(),
                  item.ticketTypeId());
              jdbc.update(
                  "UPDATE admission_pool SET sold=sold+? WHERE id=(SELECT pool_id FROM"
                      + " admission_ticket_type WHERE id=?)",
                  item.quantity(),
                  item.ticketTypeId());
            }
          }
          if (order.admissionHoldId() != null)
            jdbc.update(
                "UPDATE admission_hold SET released=true WHERE id=?", order.admissionHoldId());
          store.status(id, "CONFIRMED", payment.id());
          afterCommitRelease(order, items, true);
        });
  }

  /** Caller owns the event row lock; no customer identity is impersonated. */
  public void invalidateForEvent(long eventId) {
    for (var order : store.pendingForEvent(eventId)) {
      store.status(order.id(), "EXPIRED", null);
      afterCommitRelease(order, store.items(order.id()), false);
    }
    jdbc.update(
        "UPDATE admission_hold SET released=true WHERE event_id=? AND NOT released", eventId);
  }

  private void afterCommitRelease(
      CheckoutStore.Order order, List<CheckoutStore.Item> items, boolean booked) {
    if (!booked && order.admissionHoldId() != null)
      jdbc.update("UPDATE admission_hold SET released=true WHERE id=?", order.admissionHoldId());
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            for (var item : items)
              if (item.seatId() != null) {
                try {
                  holds.releaseCheckout(order.eventId(), item.seatId(), order.userId(), order.id());
                } catch (Exception ex) {
                  log.warn("Order {} hold release will rely on TTL", order.id());
                }
                try {
                  messages.convertAndSend(
                      "/topic/seats/" + order.eventId(),
                      (Object)
                          Map.of(
                              "eventId",
                              order.eventId(),
                              "seatId",
                              item.seatId(),
                              "status",
                              booked
                                      || bookings.existsBySeatIdAndEventId(
                                          item.seatId(), order.eventId())
                                  ? "BOOKED"
                                  : holds.isSeatHeld(order.eventId(), item.seatId())
                                      ? "HELD"
                                      : "FREE"));
                } catch (Exception ex) {
                  log.warn("Order {} notification will rely on reconciliation", order.id());
                }
              }
          }
        });
  }

  private long minor(BigDecimal price) {
    try {
      return price.movePointRight(2).longValueExact();
    } catch (ArithmeticException ex) {
      throw error(400, "Invalid ticket price");
    }
  }

  Map<String, Object> view(CheckoutStore.Order order) {
    var result = new LinkedHashMap<String, Object>();
    String status =
        Set.of("PENDING", "CREATING", "CREATE_UNKNOWN").contains(order.status())
                && order.expiresAt().isBefore(store.now())
            ? "EXPIRED"
            : order.status();
    result.put("id", order.id());
    result.put("eventId", order.eventId());
    result.put("event", eventSnapshots.get(order.eventId()));
    result.put("status", status);
    result.put("amount", order.amount());
    result.put("currency", order.currency());
    result.put("providerOrderId", order.providerOrderId());
    result.put("expiresAt", order.expiresAt());
    result.put("keyId", settings.isEnabled() ? settings.getKeyId() : "");
    result.put("mode", "test");
    result.put("items", store.items(order.id()));
    return result;
  }
}
