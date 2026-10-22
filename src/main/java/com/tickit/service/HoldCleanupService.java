package com.tickit.service;

import com.tickit.dto.SeatStatusUpdate;
import com.tickit.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class HoldCleanupService {
    private final StringRedisTemplate redis;
    private final BookingRepository bookingRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final SeatHoldService holds;

    @Scheduled(fixedDelayString = "${app.seat-hold.cleanup-interval:1000}")
    @SuppressWarnings("unchecked")
    public void releaseExpiredHolds() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/expired_holds.lua"));
        script.setResultType(List.class);
        try {
            List<String> expired = redis.execute(script, List.of("hold:expiry"));
            if (expired == null) return;
            for (String key : expired) {
                String[] parts = key.split(":");
                Long eventId = Long.valueOf(parts[1]);
                Long seatId = Long.valueOf(parts[2]);
                if (holds.isSeatHeld(eventId, seatId)) continue;
                String status = bookingRepository.existsBySeatIdAndEventId(seatId, eventId) ? "BOOKED" : "FREE";
                messagingTemplate.convertAndSend("/topic/seats/" + eventId,
                        SeatStatusUpdate.builder().eventId(eventId).seatId(seatId)
                                .seatLabel(String.valueOf(seatId)).status(status).build());
            }
        } catch (Exception ex) {
            log.warn("Hold expiration notification failed; clients reconcile via seat API", ex);
        }
    }
}
