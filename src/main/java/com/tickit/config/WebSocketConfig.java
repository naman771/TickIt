package com.tickit.config;

import com.tickit.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.*;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.*;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
  private final EventRepository events;

  @Override
  public void configureMessageBroker(MessageBrokerRegistry registry) {
    registry.enableSimpleBroker("/topic");
    registry.setApplicationDestinationPrefixes("/app");
  }

  @Override
  public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(inboundGuard());
  }

  public ChannelInterceptor inboundGuard() {
    return new ChannelInterceptor() {
      @Override
      public Message<?> preSend(Message<?> message, MessageChannel channel) {
        var headers = StompHeaderAccessor.wrap(message);
        if (headers.getCommand() == StompCommand.SEND)
          throw new MessageDeliveryException("Client publishing is not allowed");
        if (headers.getCommand() == StompCommand.SUBSCRIBE
            || headers.getCommand() == StompCommand.UNSUBSCRIBE) {
          var attributes = headers.getSessionAttributes();
          if (attributes == null) throw new MessageDeliveryException("Session is unavailable");
          synchronized (attributes) {
            long now = System.nanoTime();
            long last = (long) attributes.getOrDefault("tickit.lastFrame", now);
            double tokens =
                Math.min(
                    20,
                    (double) attributes.getOrDefault("tickit.frameTokens", 20d)
                        + (now - last) / 1e9 * 5);
            if (tokens < 1) throw new MessageDeliveryException("Session rate limit exceeded");
            attributes.put("tickit.lastFrame", now);
            attributes.put("tickit.frameTokens", tokens - 1);
            @SuppressWarnings("unchecked")
            var subscriptions =
                (java.util.Set<String>)
                    attributes.computeIfAbsent(
                        "tickit.subscriptions", key -> new java.util.HashSet<String>());
            String subscription = headers.getSubscriptionId();
            if (headers.getCommand() == StompCommand.UNSUBSCRIBE)
              subscriptions.remove(subscription);
            else {
              if (subscription == null || subscription.isBlank() || subscription.length() > 128)
                throw new MessageDeliveryException("Invalid subscription ID");
              if (!subscriptions.contains(subscription) && subscriptions.size() >= 8)
                throw new MessageDeliveryException("Too many subscriptions");
              subscriptions.add(subscription);
            }
          }
        }
        if (headers.getCommand() == StompCommand.SUBSCRIBE) {
          String destination = headers.getDestination();
          if (destination == null || !destination.matches("/topic/seats/[1-9][0-9]{0,17}"))
            throw new MessageDeliveryException("Invalid seat subscription");
          Long eventId = Long.valueOf(destination.substring("/topic/seats/".length()));
          if (!events.existsByIdAndPublicationStatus(eventId, "PUBLISHED"))
            throw new MessageDeliveryException("Event is unavailable");
        }
        return message;
      }
    };
  }

  @Override
  public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
    registration.setMessageSizeLimit(16384).setSendBufferSizeLimit(65536).setSendTimeLimit(10000);
  }

  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    registry.addEndpoint("/ws").withSockJS();
  }
}
