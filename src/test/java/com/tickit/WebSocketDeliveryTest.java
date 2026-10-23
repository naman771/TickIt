package com.tickit;

import static org.junit.jupiter.api.Assertions.*;

import com.tickit.dto.SeatStatusUpdate;
import com.tickit.model.Event;
import com.tickit.repository.EventRepository;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WebSocketDeliveryTest {
  @LocalServerPort int port;
  @Autowired EventRepository events;
  @Autowired SimpMessagingTemplate messages;
  @Autowired org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler broker;

  @Test
  void realSocketDeliversServerUpdatesAndRejectsForgedClientBroadcast() throws Exception {
    var event =
        events.saveAndFlush(
            Event.builder()
                .name("WebSocket regression")
                .startTime(LocalDateTime.now().plusDays(2))
                .build());
    String destination = "/topic/seats/" + event.getId();
    var client = new WebSocketStompClient(new StandardWebSocketClient());
    client.setMessageConverter(new MappingJackson2MessageConverter());
    StompSession subscriber = null, attacker = null;
    var queue = new LinkedBlockingQueue<SeatStatusUpdate>();
    var denied = new CompletableFuture<Boolean>();
    try {
      subscriber =
          client
              .connectAsync(
                  "ws://localhost:" + port + "/ws/websocket", new StompSessionHandlerAdapter() {})
              .get(10, TimeUnit.SECONDS);
      var subscription =
          subscriber.subscribe(
              destination,
              new StompFrameHandler() {
                public java.lang.reflect.Type getPayloadType(StompHeaders h) {
                  return SeatStatusUpdate.class;
                }

                public void handleFrame(StompHeaders h, Object payload) {
                  queue.add((SeatStatusUpdate) payload);
                }
              });
      var probe =
          org.springframework.messaging.support.MessageBuilder.withPayload(new byte[0])
              .setHeader(
                  org.springframework.messaging.simp.SimpMessageHeaderAccessor.DESTINATION_HEADER,
                  destination)
              .setHeader(
                  org.springframework.messaging.simp.SimpMessageHeaderAccessor.MESSAGE_TYPE_HEADER,
                  org.springframework.messaging.simp.SimpMessageType.MESSAGE)
              .build();
      org.awaitility.Awaitility.await()
          .atMost(java.time.Duration.ofSeconds(5))
          .until(() -> !broker.getSubscriptionRegistry().findSubscriptions(probe).isEmpty());
      messages.convertAndSend(
          destination,
          SeatStatusUpdate.builder().eventId(event.getId()).seatId(1L).status("HELD").build());
      assertEquals("HELD", queue.poll(5, TimeUnit.SECONDS).getStatus());
      attacker =
          client
              .connectAsync(
                  "ws://localhost:" + port + "/ws/websocket",
                  new StompSessionHandlerAdapter() {
                    @Override
                    public void handleFrame(StompHeaders h, Object p) {
                      denied.complete(true);
                    }

                    @Override
                    public void handleTransportError(StompSession s, Throwable e) {
                      denied.complete(true);
                    }
                  })
              .get(10, TimeUnit.SECONDS);
      attacker.send(
          destination,
          SeatStatusUpdate.builder().eventId(event.getId()).seatId(1L).status("FORGED").build());
      assertTrue(denied.get(10, TimeUnit.SECONDS));
      messages.convertAndSend(
          destination,
          SeatStatusUpdate.builder().eventId(event.getId()).seatId(1L).status("BOOKED").build());
      assertEquals("BOOKED", queue.poll(5, TimeUnit.SECONDS).getStatus());
      assertNull(queue.poll(100, TimeUnit.MILLISECONDS));
    } finally {
      if (subscriber != null && subscriber.isConnected()) subscriber.disconnect();
      if (attacker != null && attacker.isConnected()) attacker.disconnect();
      client.stop();
      events.deleteById(event.getId());
    }
  }
}
