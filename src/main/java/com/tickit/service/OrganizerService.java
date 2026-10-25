package com.tickit.service;

import com.tickit.dto.OrganizerEventRequest;
import com.tickit.model.*;
import com.tickit.repository.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class OrganizerService {
  private final CurrentUserService currentUser;
  private final com.tickit.notification.NotificationOutbox notifications;
  private final BookingRepository bookings;
  private final SeatHoldService seatHolds;
  private final AdmissionHoldRepository admissionHolds;
  private final org.springframework.jdbc.core.JdbcTemplate jdbc;
  private final com.tickit.payment.CheckoutService checkout;
  private final com.tickit.payment.RefundService refunds;
  private final org.springframework.messaging.simp.SimpMessagingTemplate messages;
  private final jakarta.persistence.EntityManager entityManager;
  private final OrganizerAccountRepository organizers;
  private final EventRepository events;
  private final VenueRepository venues;
  private final SeatRepository seats;
  private final AdmissionPoolRepository pools;
  private final AdmissionTicketTypeRepository tickets;

  @Transactional
  public OrganizerAccount enroll(String name) {
    if (name.trim().length() < 2) throw bad("Organizer name must contain at least two characters");
    AppUser user = currentUser.resolve(null);
    OrganizerAccount account = organizers.findById(user.getId()).orElseGet(OrganizerAccount::new);
    account.setUser(entityManager.getReference(AppUser.class, user.getId()));
    account.setDisplayName(name.trim());
    return organizers.saveAndFlush(account);
  }

  public OrganizerAccount account() {
    return organizers
        .findById(currentUser.resolve(null).getId())
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Create your organizer workspace first"));
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> list() {
    return events.findByOrganizerIdOrderByCreatedAtDesc(account().getId()).stream()
        .filter(e -> !"DELETED".equals(e.getPublicationStatus()))
        .map(this::view)
        .toList();
  }

  @Transactional(readOnly = true)
  public Map<String, Object> get(Long id) {
    return view(owned(id, false));
  }

  private Event owned(Long id, boolean locked) {
    Long owner = account().getId();
    Event event =
        (locked ? events.lockById(id) : events.findById(id))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if ("DELETED".equals(event.getPublicationStatus()))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    if (event.getOrganizer() == null || !event.getOrganizer().getId().equals(owner))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    return event;
  }

  @Transactional
  public Map<String, Object> save(Long id, OrganizerEventRequest input) {
    OrganizerAccount owner = account();
    Event event = id == null ? new Event() : owned(id, true);
    if (id != null && !Objects.equals(event.getLayoutVersion(), input.version()))
      throw conflict("This draft changed in another tab. Reload before saving.");
    validate(input);
    if (id != null && !"DRAFT".equals(event.getPublicationStatus())) {
      if (!"PUBLISHED".equals(event.getPublicationStatus()))
        throw conflict("Cancelled events cannot be edited; duplicate to create a new event");
      return amend(event, input);
    }
    event.setUpdatedAt(Instant.now());
    event.setOrganizer(owner);
    event.setPublicationStatus("DRAFT");
    event.setCurrency("INR");
    event.setName(input.name().trim());
    event.setDescription(input.description());
    applyInformation(event, input.information());
    if (input.cancellationPolicy() != null) {
      if (Boolean.TRUE.equals(input.cancellationPolicy().enabled())
          && input.cancellationPolicy().cutoffHours() == null)
        throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.BAD_REQUEST,
            "Choose a cancellation cutoff between 1 and 720 hours");
      event.setCancellationCutoffHours(
          Boolean.TRUE.equals(input.cancellationPolicy().enabled())
              ? input.cancellationPolicy().cutoffHours()
              : null);
    }
    event.setCategory(input.category());
    event.setAdmissionMode(input.admissionMode());
    event.setCity(input.city().trim());
    event.setTimezone(input.timezone());
    event.setSalesStart(input.salesStart());
    event.setSalesEnd(input.salesEnd());
    event.setPerOrderLimit(input.perOrderLimit());
    event.setMinimumAge(input.minimumAge());
    event.setStartTime(input.startTime());
    event.setEndTime(input.endTime());
    event.setGuestCapacity(input.guestCapacity());
    event.setImageUrl(input.imageUrl() == null ? null : input.imageUrl().trim());
    event.setStageLabel(
        input.stageLabel() == null || input.stageLabel().isBlank()
            ? "Stage"
            : input.stageLabel().trim());
    event.setStageX(input.stageX());
    event.setStageY(input.stageY());
    event = events.saveAndFlush(event);
    // Drafts have no bookings or holds. Replacement is atomic and protected by the event row lock.
    if (id != null) {
      for (Venue old : venues.findByEventId(id)) venues.delete(old);
      for (AdmissionPool old : pools.findByEventIdOrderById(id)) {
        tickets.deleteAll(tickets.findByPoolIdOrderById(old.getId()));
        tickets.flush();
        pools.delete(old);
      }
      venues.flush();
      pools.flush();
    }
    Venue venue =
        Venue.builder()
            .name(input.venueName().trim())
            .location(input.address().trim())
            .event(event)
            .build();
    venue = venues.saveAndFlush(venue);
    for (var seat : input.seats())
      seats.save(
          Seat.builder()
              .venue(venue)
              .section(seat.section().trim())
              .seatRow(seat.row().trim())
              .seatNumber(seat.number())
              .x(seat.x())
              .y(seat.y())
              .price(seat.price())
              .build());
    for (var poolInput : input.pools()) {
      AdmissionPool pool = new AdmissionPool();
      pool.setEvent(event);
      pool.setName(poolInput.name().trim());
      pool.setCapacity(poolInput.capacity());
      pool = pools.saveAndFlush(pool);
      for (var type : poolInput.tickets()) {
        AdmissionTicketType ticket = new AdmissionTicketType();
        ticket.setPool(pool);
        ticket.setName(type.name().trim());
        ticket.setPrice(type.price());
        ticket.setQuota(type.quota());
        tickets.save(ticket);
      }
    }
    seats.flush();
    tickets.flush();
    return view(event);
  }

  private Map<String, Object> amend(Event event, OrganizerEventRequest input) {
    Long id = event.getId();
    boolean rescheduled =
        !Objects.equals(event.getStartTime(), input.startTime())
            || !Objects.equals(event.getEndTime(), input.endTime())
            || !event.getTimezone().equals(input.timezone());
    if (!input.startTime().atZone(ZoneId.of(input.timezone())).toInstant().isAfter(Instant.now()))
      throw bad("The event must start in the future");
    if (!event.getAdmissionMode().equals(input.admissionMode()))
      throw conflict("Admission format cannot change after publishing");
    if (!samePools(event, input))
      throw conflict("Published admission zones and quotas cannot be replaced");
    if (rescheduled && (input.changeReason() == null || input.changeReason().isBlank()))
      throw bad("Explain the date or time change for ticket holders");
    var existing = seats.findByVenueEventId(id);
    Integer active =
        jdbc.queryForObject(
            "SELECT count(*) FROM checkout_order WHERE event_id=? AND status IN"
                + " ('CREATING','CREATE_UNKNOWN','PENDING') AND expires_at>clock_timestamp()",
            Integer.class,
            id);
    boolean held =
        !seatHolds.heldSeatIds(id, existing.stream().map(Seat::getId).toList()).isEmpty()
            || pools.findByEventIdOrderById(id).stream()
                .anyMatch(p -> admissionHolds.activeInPool(p.getId()) > 0)
            || (active != null && active > 0);
    // A reschedule invalidates checkout; inventory changes wait for active reservations to end.
    if (held && !rescheduled)
      throw conflict("Guests have active reservations. Retry after their holds expire");
    if (rescheduled && held && !sameSeats(existing, input.seats()))
      throw conflict("Reschedule first; change seats after reservations have expired");
    Map<Long, Seat> byId = new HashMap<>();
    Map<String, Seat> byLabel = new HashMap<>();
    for (var seat : existing) {
      byId.put(seat.getId(), seat);
      byLabel.put(label(seat.getSection(), seat.getSeatRow(), seat.getSeatNumber()), seat);
    }
    Set<Long> retained = new HashSet<>();
    var changes = new LinkedHashMap<Seat, OrganizerEventRequest.SeatInput>();
    var venue = venues.findByEventId(id).stream().findFirst().orElseThrow();
    for (var item : input.seats()) {
      Seat seat =
          item.id() != null
              ? byId.get(item.id())
              : byLabel.get(label(item.section(), item.row(), item.number()));
      if (item.id() != null && seat == null)
        throw bad("A seat ID does not belong to this active event layout");
      if (seat != null) {
        if (!retained.add(seat.getId())) throw bad("Duplicate seat ID");
        if (bookings.existsBySeatIdAndEventId(seat.getId(), id)
            && !label(seat.getSection(), seat.getSeatRow(), seat.getSeatNumber())
                .equals(label(item.section(), item.row(), item.number())))
          throw conflict("Booked seat labels cannot change");
      } else {
        seat = new Seat();
        seat.setVenue(venue);
      }
      changes.put(seat, item);
    }
    for (var seat : existing)
      if (!retained.contains(seat.getId())) {
        if (bookings.existsBySeatIdAndEventId(seat.getId(), id))
          throw conflict(
              "Booked seats cannot be deleted; cancel the event if it will not take place");
        seat.setRetired(true);
      }
    seats.flush();
    // Temporarily remove unchanged IDs from the active-label index to permit atomic label swaps.
    for (var seat : changes.keySet()) if (seat.getId() != null) seat.setRetired(true);
    seats.flush();
    for (var entry : changes.entrySet()) {
      var seat = entry.getKey();
      var item = entry.getValue();
      seat.setSection(item.section().trim());
      seat.setSeatRow(item.row().trim());
      seat.setSeatNumber(item.number());
      seat.setX(item.x());
      seat.setY(item.y());
      seat.setPrice(item.price());
      seat.setRetired(false);
      seats.save(seat);
    }
    seats.flush();
    if (rescheduled) {
      checkout.invalidateForEvent(id);
      audit(event, "RESCHEDULED", input.changeReason(), input.startTime(), input.timezone());
      event.setScheduleChangedAt(Instant.now());
      event.setChangeNote(input.changeReason().trim());
    }
    event.setName(input.name().trim());
    event.setDescription(input.description());
    applyInformation(event, input.information());
    if (input.cancellationPolicy() != null) {
      if (Boolean.TRUE.equals(input.cancellationPolicy().enabled())
          && input.cancellationPolicy().cutoffHours() == null)
        throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.BAD_REQUEST,
            "Choose a cancellation cutoff between 1 and 720 hours");
      event.setCancellationCutoffHours(
          Boolean.TRUE.equals(input.cancellationPolicy().enabled())
              ? input.cancellationPolicy().cutoffHours()
              : null);
    }
    event.setCategory(input.category());
    event.setCity(input.city().trim());
    event.setStartTime(input.startTime());
    event.setEndTime(input.endTime());
    event.setTimezone(input.timezone());
    event.setSalesStart(input.salesStart());
    event.setSalesEnd(input.salesEnd());
    event.setGuestCapacity(input.guestCapacity());
    event.setPerOrderLimit(input.perOrderLimit());
    event.setMinimumAge(input.minimumAge());
    event.setImageUrl(input.imageUrl());
    event.setStageLabel(input.stageLabel());
    event.setStageX(input.stageX());
    event.setStageY(input.stageY());
    event.setUpdatedAt(Instant.now());
    venue.setName(input.venueName().trim());
    venue.setLocation(input.address().trim());
    venues.save(venue);
    events.saveAndFlush(event);
    notifyAfterCommit(id);
    return view(event);
  }

  private String label(String section, String row, int number) {
    return section.trim() + "/" + row.trim() + "/" + number;
  }

  private boolean sameSeats(List<Seat> existing, List<OrganizerEventRequest.SeatInput> input) {
    if (existing.size() != input.size()) return false;
    return input.stream()
        .allMatch(
            i ->
                existing.stream()
                    .anyMatch(
                        s ->
                            (i.id() == null || i.id().equals(s.getId()))
                                && label(s.getSection(), s.getSeatRow(), s.getSeatNumber())
                                    .equals(label(i.section(), i.row(), i.number()))
                                && Objects.equals(s.getX(), i.x())
                                && Objects.equals(s.getY(), i.y())
                                && s.getPrice().compareTo(i.price()) == 0));
  }

  private boolean samePools(Event event, OrganizerEventRequest input) {
    var old = pools.findByEventIdOrderById(event.getId());
    if (old.size() != input.pools().size()) return false;
    for (var p : old) {
      var next =
          input.pools().stream()
              .filter(x -> x.name().trim().equals(p.getName()))
              .findFirst()
              .orElse(null);
      if (next == null || next.capacity() != p.getCapacity()) return false;
      var types = tickets.findByPoolIdOrderById(p.getId());
      if (types.size() != next.tickets().size()) return false;
      for (var t : types)
        if (next.tickets().stream()
            .noneMatch(
                x ->
                    x.name().trim().equals(t.getName())
                        && x.quota() == t.getQuota()
                        && x.price().compareTo(t.getPrice()) == 0)) return false;
    }
    return true;
  }

  @Transactional
  public Map<String, Object> remove(Long id, Long revision, String reason, boolean delete) {
    Event event = owned(id, true);
    if (!Objects.equals(event.getLayoutVersion(), revision))
      throw conflict("Event changed in another tab. Reload before continuing");
    if (reason == null || reason.isBlank() || reason.length() > 1000)
      throw bad("Provide a reason of up to 1000 characters");
    if (!delete && !"PUBLISHED".equals(event.getPublicationStatus()))
      throw conflict("Only a published event can be cancelled");
    checkout.invalidateForEvent(id);
    audit(
        event, delete ? "DELETED" : "CANCELLED", reason, event.getStartTime(), event.getTimezone());
    event.setPublicationStatus(delete ? "DELETED" : "CANCELLED");
    event.setChangeNote(reason.trim());
    event.setUpdatedAt(Instant.now());
    events.saveAndFlush(event);
    refunds.enqueueEligible(id);
    notifyAfterCommit(id);
    return view(event);
  }

  private void audit(Event e, String action, String reason, LocalDateTime next, String timezone) {
    jdbc.update(
        "INSERT INTO"
            + " event_change(event_id,action,reason,previous_start,new_start,previous_timezone,new_timezone)"
            + " VALUES(?,?,?,?,?,?,?)",
        e.getId(),
        action,
        reason,
        e.getStartTime(),
        next,
        e.getTimezone(),
        timezone);
    Long change =
        jdbc.queryForObject(
            "SELECT id FROM event_change WHERE event_id=? ORDER BY id DESC LIMIT 1",
            Long.class,
            e.getId());
    notifications.eventChange(change);
  }

  private void notifyAfterCommit(Long id) {
    org.springframework.transaction.support.TransactionSynchronizationManager
        .registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() {
              @Override
              public void afterCommit() {
                try {
                  messages.convertAndSend(
                      "/topic/seats/" + id,
                      (Object) Map.of("eventId", id, "status", "EVENT_CHANGED"));
                } catch (Exception ignored) {
                  /* Customer polling reconciles. */
                }
              }
            });
  }

  private void validate(OrganizerEventRequest i) {
    try {
      ZoneId.of(i.timezone());
    } catch (DateTimeException e) {
      throw bad("Choose a valid timezone");
    }
    var zone = ZoneId.of(i.timezone());
    for (var time :
        java.util.stream.Stream.of(i.startTime(), i.endTime(), i.salesStart(), i.salesEnd())
            .filter(Objects::nonNull)
            .toList())
      if (zone.getRules().getValidOffsets(time).size() != 1)
        throw bad("A selected local time is ambiguous or does not exist in this timezone");
    if (i.salesEnd() != null && i.salesEnd().isAfter(i.startTime()))
      throw bad("Ticket sales must close by the event start");
    if (i.salesStart() != null
        && !i.salesStart().isBefore(i.salesEnd() == null ? i.startTime() : i.salesEnd()))
      throw bad("Sales opening must precede sales closing");
    if (i.endTime() != null && !i.endTime().isAfter(i.startTime()))
      throw bad("End time must be after start time");
    if (i.imageUrl() != null && !i.imageUrl().isBlank()) {
      try {
        var uri = java.net.URI.create(i.imageUrl());
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
          throw new IllegalArgumentException();
      } catch (IllegalArgumentException e) {
        throw bad("Cover image must be an HTTPS URL");
      }
    }
    Set<String> seatLabels = new HashSet<>();
    Map<String, List<OrganizerEventRequest.SeatInput>> cells = new HashMap<>();
    for (var s : i.seats()) {
      if (!seatLabels.add(s.section().trim() + "/" + s.row().trim() + "/" + s.number()))
        throw bad("Duplicate seat label within a section");
      int cx = s.x() / 22, cy = s.y() / 22;
      for (int x = cx - 1; x <= cx + 1; x++)
        for (int y = cy - 1; y <= cy + 1; y++)
          for (var other : cells.getOrDefault(x + ":" + y, List.of()))
            if (Math.abs(other.x() - s.x()) < 22 && Math.abs(other.y() - s.y()) < 22)
              throw bad("Seat positions overlap; leave room for each 22 × 22 seat");
      cells.computeIfAbsent(cx + ":" + cy, key -> new ArrayList<>()).add(s);
    }
    Set<String> poolNames = new HashSet<>();
    for (var p : i.pools()) {
      if (!poolNames.add(p.name().trim())) throw bad("Duplicate admission zone name");
      Set<String> names = new HashSet<>();
      for (var t : p.tickets()) {
        if (t.quota() > p.capacity())
          throw bad("A ticket quota cannot exceed its shared zone capacity");
        if (!names.add(t.name().trim())) throw bad("Duplicate ticket type in a zone");
      }
    }
    if ("SEATED".equals(i.admissionMode()) && !i.pools().isEmpty())
      throw bad("Assigned seating cannot contain standing zones");
    if ("GENERAL".equals(i.admissionMode()) && !i.seats().isEmpty())
      throw bad("General admission cannot contain assigned seats");
    long inventory =
        i.seats().size()
            + i.pools().stream().mapToLong(OrganizerEventRequest.PoolInput::capacity).sum();
    if (inventory > i.guestCapacity())
      throw bad("Seat and zone capacity exceeds the event guest capacity");
  }

  @Transactional
  public Map<String, Object> publish(Long id, Long revision) {
    Event event = owned(id, true);
    if (!"DRAFT".equals(event.getPublicationStatus())) throw conflict("Event is already published");
    if (!Objects.equals(revision, event.getLayoutVersion()))
      throw conflict("Reload the latest saved draft before publishing");
    if (event.getDescription() == null || event.getDescription().trim().length() < 20)
      throw bad("Add an event description of at least 20 characters");
    if (!event
        .getStartTime()
        .atZone(ZoneId.of(event.getTimezone()))
        .toInstant()
        .isAfter(Instant.now())) throw bad("Event must start in the future");
    int seatCount = seats.findByVenueEventId(id).size();
    boolean hasPools = !pools.findByEventIdOrderById(id).isEmpty();
    if (!"GENERAL".equals(event.getAdmissionMode()) && seatCount == 0)
      throw bad("Create the event's seat layout before publishing");
    if (!"SEATED".equals(event.getAdmissionMode()) && !hasPools)
      throw bad("Add at least one admission zone with tickets");
    event.setPublicationStatus("PUBLISHED");
    events.saveAndFlush(event);
    return view(event);
  }

  private void applyInformation(Event event, OrganizerEventRequest.Information info) {
    // Older editor clients omit this object; preserve existing optional information.
    if (info == null) return;
    event.setLanguages(info.languages());
    event.setArtists(info.artists());
    event.setEntryInstructions(info.entryInstructions());
    event.setAccessibilityInfo(info.accessibilityInfo());
    event.setTicketInclusions(info.ticketInclusions());
    event.setFaq(info.faq());
  }

  private Map<String, Object> view(Event e) {
    var venue = venues.findByEventId(e.getId()).stream().findFirst().orElse(null);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", e.getId());
    data.put("name", e.getName());
    data.put("slug", e.getSlug());
    data.put("description", e.getDescription());
    data.put(
        "information",
        new OrganizerEventRequest.Information(
            e.getLanguages(),
            e.getArtists(),
            e.getEntryInstructions(),
            e.getAccessibilityInfo(),
            e.getTicketInclusions(),
            e.getFaq()));
    data.put("category", e.getCategory());
    data.put("admissionMode", e.getAdmissionMode());
    data.put("status", e.getPublicationStatus());
    data.put("currency", e.getCurrency());
    data.put("venueName", venue == null ? "" : venue.getName());
    data.put("address", venue == null ? "" : venue.getLocation());
    data.put("city", e.getCity());
    data.put("salesStart", e.getSalesStart());
    data.put("salesEnd", e.getSalesEnd());
    data.put("perOrderLimit", Math.min(3, e.getPerOrderLimit()));
    data.put("minimumAge", e.getMinimumAge());
    data.put(
        "cancellationPolicy",
        Map.of(
            "enabled",
            e.getCancellationCutoffHours() != null,
            "cutoffHours",
            e.getCancellationCutoffHours() == null ? 24 : e.getCancellationCutoffHours()));
    data.put("startTime", e.getStartTime());
    data.put("endTime", e.getEndTime());
    data.put("timezone", e.getTimezone());
    data.put("guestCapacity", e.getGuestCapacity());
    data.put("imageUrl", e.getImageUrl());
    data.put("stageLabel", e.getStageLabel());
    data.put("stageX", e.getStageX());
    data.put("stageY", e.getStageY());
    data.put("version", e.getLayoutVersion());
    data.put("changeReason", e.getChangeNote());
    data.put("scheduleChanged", e.getScheduleChangedAt() != null);
    data.put(
        "bookedSeatIds",
        bookings.findByEventId(e.getId()).stream().map(b -> b.getSeat().getId()).toList());
    data.put(
        "seats",
        seats.findByVenueEventId(e.getId()).stream()
            .map(
                s ->
                    new OrganizerEventRequest.SeatInput(
                        s.getId(),
                        s.getSection(),
                        s.getSeatRow(),
                        s.getSeatNumber(),
                        s.getX() == null ? 0 : s.getX(),
                        s.getY() == null ? 0 : s.getY(),
                        s.getPrice()))
            .toList());
    data.put(
        "pools",
        pools.findByEventIdOrderById(e.getId()).stream()
            .map(
                p ->
                    new OrganizerEventRequest.PoolInput(
                        p.getName(),
                        p.getCapacity(),
                        tickets.findByPoolIdOrderById(p.getId()).stream()
                            .map(
                                t ->
                                    new OrganizerEventRequest.TicketInput(
                                        t.getName(), t.getPrice(), t.getQuota()))
                            .toList()))
            .toList());
    return data;
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}
