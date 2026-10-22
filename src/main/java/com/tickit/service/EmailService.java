package com.tickit.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.mail.internet.MimeMessage;

/**
 * Sends HTML booking-confirmation emails asynchronously.
 * Disabled by default (app.email.enabled=false) so the app
 * runs without an SMTP server during development.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${app.email.from:noreply@tickit.live}")
    private String fromAddress;

    @Value("${app.email.enabled:false}")
    private boolean emailEnabled;

    @Async
    public void sendBookingConfirmation(String toEmail, String userName,
                                        String eventName, String seatLabel,
                                        Long bookingId) {
        if (!emailEnabled) {
            log.info("[EMAIL DISABLED] Would send confirmation to {} for booking #{}", toEmail, bookingId);
            return;
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromAddress);
            helper.setTo(toEmail);
            helper.setSubject("🎫 Booking Confirmed – " + eventName);

            String html = buildEmailHtml(userName, eventName, seatLabel, bookingId);
            helper.setText(html, true);

            mailSender.send(message);
            log.info("Confirmation email sent to {} for booking #{}", toEmail, bookingId);
        } catch (Exception e) {
            log.error("Failed to send confirmation email to {} for booking #{}", toEmail, bookingId, e);
            // In production: persist to a dead-letter table and retry
        }
    }

    private String buildEmailHtml(String userName, String eventName,
                                   String seatLabel, Long bookingId) {
        return """
                <div style="font-family:Arial,sans-serif;max-width:600px;margin:0 auto;
                            background:#1a1a2e;color:#eee;border-radius:12px;overflow:hidden">
                  <div style="background:linear-gradient(135deg,#6c63ff,#e94560);padding:32px;text-align:center">
                    <h1 style="margin:0;font-size:28px">🎫 Booking Confirmed!</h1>
                  </div>
                  <div style="padding:24px">
                    <p>Hi <strong>%s</strong>,</p>
                    <p>Your booking has been confirmed. Here are the details:</p>
                    <table style="width:100%%;border-collapse:collapse;margin:16px 0">
                      <tr><td style="padding:8px;color:#aaa">Event</td>
                          <td style="padding:8px;font-weight:bold">%s</td></tr>
                      <tr><td style="padding:8px;color:#aaa">Seat</td>
                          <td style="padding:8px;font-weight:bold">%s</td></tr>
                      <tr><td style="padding:8px;color:#aaa">Booking ID</td>
                          <td style="padding:8px;font-weight:bold">#%d</td></tr>
                    </table>
                    <p style="color:#aaa;font-size:12px">
                      This is an automated message from TickIt.
                    </p>
                  </div>
                </div>
                """.formatted(org.springframework.web.util.HtmlUtils.htmlEscape(userName),
                        org.springframework.web.util.HtmlUtils.htmlEscape(eventName),
                        org.springframework.web.util.HtmlUtils.htmlEscape(seatLabel), bookingId);
    }
}
