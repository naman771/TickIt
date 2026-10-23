package com.tickit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.tickit.config.WebSocketConfig;
import com.tickit.repository.EventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.*;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.MessageBuilder;

class WebSocketBoundaryTest {
  private final EventRepository events = mock(EventRepository.class);
  private final WebSocketConfig config = new WebSocketConfig(events);

  private final java.util.Map<String, Object> attributes = new java.util.HashMap<>();

  private Message<byte[]> frame(StompCommand command, String destination) {
    var headers = StompHeaderAccessor.create(command);
    headers.setDestination(destination);
    headers.setSessionAttributes(attributes);
    headers.setSubscriptionId(destination);
    return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
  }

  @Test
  void clientsCannotForgeSeatBroadcastsOrApplicationMessages() {
    for (String destination : new String[] {"/topic/seats/1", "/app/seats", "/queue/private"})
      assertThrows(
          MessageDeliveryException.class,
          () -> config.inboundGuard().preSend(frame(StompCommand.SEND, destination), null));
    verifyNoInteractions(events);
  }

  @Test
  void onlyPublishedSeatTopicsCanBeSubscribedTo() {
    when(events.existsByIdAndPublicationStatus(1L, "PUBLISHED")).thenReturn(true);
    var message = frame(StompCommand.SUBSCRIBE, "/topic/seats/1");
    assertSame(message, config.inboundGuard().preSend(message, null));
    assertThrows(
        MessageDeliveryException.class,
        () -> config.inboundGuard().preSend(frame(StompCommand.SUBSCRIBE, "/topic/seats/2"), null));
    for (String destination :
        new String[] {
          "/topic/**",
          "/topic/seats/0",
          "/topic/seats/-1",
          "/topic/seats/999999999999999999999",
          "/user/queue/private"
        })
      assertThrows(
          MessageDeliveryException.class,
          () -> config.inboundGuard().preSend(frame(StompCommand.SUBSCRIBE, destination), null));
  }

  @Test
  void subscriptionBudgetIsBoundedAndUnsubscribeFreesASlot() {
    when(events.existsByIdAndPublicationStatus(anyLong(), eq("PUBLISHED"))).thenReturn(true);
    for (int i = 1; i <= 8; i++)
      config.inboundGuard().preSend(frame(StompCommand.SUBSCRIBE, "/topic/seats/" + i), null);
    assertThrows(
        MessageDeliveryException.class,
        () -> config.inboundGuard().preSend(frame(StompCommand.SUBSCRIBE, "/topic/seats/9"), null));
    config.inboundGuard().preSend(frame(StompCommand.UNSUBSCRIBE, "/topic/seats/1"), null);
    assertDoesNotThrow(
        () -> config.inboundGuard().preSend(frame(StompCommand.SUBSCRIBE, "/topic/seats/9"), null));
  }

  @Test
  void reconnectSessionStateCannotBeUsedForUnlimitedFrameBursts() {
    when(events.existsByIdAndPublicationStatus(anyLong(), eq("PUBLISHED"))).thenReturn(true);
    assertThrows(
        MessageDeliveryException.class,
        () -> {
          for (int i = 0; i < 1000; i++)
            config.inboundGuard().preSend(frame(StompCommand.SUBSCRIBE, "/topic/seats/1"), null);
        });
  }
}
