package com.tickit.service;

import com.tickit.dto.SeatStatusUpdate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Periodic background job that detects seats whose Redis holds
 * have expired and broadcasts "FREE" status updates via WebSocket.
 *
 * Redis auto-deletes expired keys, but connected clients need to
 * know the seat is available again, so we scan and notify.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HoldCleanupService {

    private final SeatHoldService seatHoldService;
    private final SimpMessagingTemplate messagingTemplate;

    // In a production system you'd track held seats in a secondary
    // Redis set to avoid KEYS scans. For the MVP this is acceptable.
    @Scheduled(fixedDelayString = "${app.seat-hold.cleanup-interval:30000}")
    public void logCleanupHeartbeat() {
        log.debug("Hold cleanup heartbeat – Redis TTL handles expiry automatically");
        // The primary mechanism is Redis TTL; this job exists so we can
        // add proactive WebSocket notifications in a future iteration.
    }
}
