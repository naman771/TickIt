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
    if (event.getOrganizer() == null || !event.getOrganizer().getId().equals(owner))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    return event;
  }

  @Transactional
  public Map<String, Object> save(Long id, OrganizerEventRequest input) {
    OrganizerAccount owner = account();
    Event event = id == null ? new Event() : owned(id, true);
    if (id != null && !"DRAFT".equals(event.getPublicationStatus()))
      throw conflict("Published events are locked. Create a new draft for a different layout.");
    if (id != null && !Objects.equals(event.getLayoutVersion(), input.version()))
      throw conflict("This draft changed in another tab. Reload before saving.");
    validate(input);
    event.setUpdatedAt(Instant.now());
    event.setOrganizer(owner);
    event.setPublicationStatus("DRAFT");
    event.setCurrency("INR");
    event.setName(input.name().trim());
    event.setDescription(input.description());
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

  private Map<String, Object> view(Event e) {
    var venue = venues.findByEventId(e.getId()).stream().findFirst().orElse(null);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", e.getId());
    data.put("name", e.getName());
    data.put("description", e.getDescription());
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
    data.put("startTime", e.getStartTime());
    data.put("endTime", e.getEndTime());
    data.put("timezone", e.getTimezone());
    data.put("guestCapacity", e.getGuestCapacity());
    data.put("imageUrl", e.getImageUrl());
    data.put("stageLabel", e.getStageLabel());
    data.put("stageX", e.getStageX());
    data.put("stageY", e.getStageY());
    data.put("version", e.getLayoutVersion());
    data.put(
        "seats",
        seats.findByVenueEventId(e.getId()).stream()
            .map(
                s ->
                    new OrganizerEventRequest.SeatInput(
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
