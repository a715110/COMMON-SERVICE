package com.dodaso.ecosystem.common.service;

import com.dodaso.ecosystem.common.entity.SystemMessagingConfig;
import com.dodaso.ecosystem.common.entity.TeamsNotificationLog;
import com.dodaso.ecosystem.common.repository.SystemMessagingConfigRepository;
import com.dodaso.ecosystem.common.repository.TeamsNotificationLogRepository;
import com.dodaso.ecosystem.common.repository.UserMessagingIdentifierRepository;
import com.dodaso.ecosystem.ecws.dto.SendResultDTO;
import com.dodaso.ecosystem.ecws.dto.TeamsNotifyDTO;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * ADDED 2026-10-24. The Teams notification calls that ecws-service makes (task events pushed to a
 * Teams Workflows webhook), moved next to the messaging tables in the common schema. ecws-service
 * builds the card; this service finds the recipient's Teams address, stops duplicates, posts the
 * card to the webhook and writes the teams_notification_log row. The webhook address stays here
 * and never leaves common-service.
 */
@Service
@Slf4j
public class TeamsNotificationService {

  private static final String CHANNEL = "TEAMS";

  @Autowired
  private SystemMessagingConfigRepository configRepository;

  @Autowired
  private UserMessagingIdentifierRepository identifierRepository;

  @Autowired
  private TeamsNotificationLogRepository logRepository;

  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10)).build();

  /** Teams address of the login, or null when none is mapped. */
  public TeamsNotifyDTO recipient(final String loginId) {
    final TeamsNotifyDTO reply = new TeamsNotifyDTO();
    reply.setLoginId(loginId);
    if (StringUtils.isNotBlank(loginId)) {
      reply.setIdentifier(identifierRepository.findByLoginIdAndChannelType(loginId, CHANNEL)
          .map(i -> i.getIdentifier()).orElse(null));
    }
    return reply;
  }

  /** True when a SENT row already exists for the event key. */
  public TeamsNotifyDTO alreadySent(final String eventKey) {
    final TeamsNotifyDTO reply = new TeamsNotifyDTO();
    reply.setEventKey(eventKey);
    reply.setAlreadySent(StringUtils.isNotBlank(eventKey)
        && logRepository.existsByEventKeyAndStatus(eventKey, "SENT"));
    return reply;
  }

  /**
   * Posts the card in the request to the webhook and writes a log row either way. A request
   * without a payload only writes the log row (status and detail as given).
   */
  public SendResultDTO post(final TeamsNotifyDTO request) {
    String status = StringUtils.defaultIfBlank(request.getStatus(), "FAILED");
    String detail = request.getDetail();
    if (StringUtils.isNotBlank(request.getPayload())) {
      try {
        final String webhook = webhookUrl(request.getWebhookOverride());
        if (StringUtils.isBlank(webhook)) {
          status = "SKIPPED";
          detail = "No Teams webhook address (ecws.teams-events.webhook-url or TEAMS row)";
        } else {
          final HttpResponse<String> response = http.send(HttpRequest.newBuilder()
              .uri(URI.create(webhook))
              .timeout(Duration.ofSeconds(20))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(request.getPayload(),
                  StandardCharsets.UTF_8))
              .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
          if (response.statusCode() / 100 == 2) {
            status = "SENT";
            detail = "HTTP " + response.statusCode();
          } else {
            status = "FAILED";
            detail = "HTTP " + response.statusCode() + " "
                + StringUtils.abbreviate(response.body(), 200);
            log.warn("Teams webhook refused {}: {}", request.getEventType(), detail);
          }
        }
      } catch (final Exception e) {
        status = "FAILED";
        detail = StringUtils.abbreviate(e.getClass().getSimpleName() + ": " + e.getMessage(), 480);
        log.warn("Teams notification failed for {}: {}", request.getEventType(), detail);
      }
    }
    record(request, status, detail);
    return "SENT".equals(status) ? SendResultDTO.success("Reminder sent to Teams")
        : SendResultDTO.failure(detail);
  }

  private String webhookUrl(final String override) {
    if (StringUtils.isNotBlank(override)) {
      return override;
    }
    return configRepository.findByChannelTypeAndActiveIndTrue(CHANNEL)
        .map(SystemMessagingConfig::getWebhookUrl).orElse(null);
  }

  private void record(final TeamsNotifyDTO request, final String status, final String detail) {
    try {
      final TeamsNotificationLog row = new TeamsNotificationLog();
      row.setEventKey(StringUtils.abbreviate(request.getEventKey(), 250));
      row.setEventType(StringUtils.abbreviate(request.getEventType(), 150));
      row.setTaskId(request.getTaskId());
      row.setRecipientLogin(request.getRecipientLogin());
      row.setStatus(status);
      row.setDetail(StringUtils.abbreviate(detail, 500));
      row.setSentAt(Instant.now());
      logRepository.save(row);
    } catch (final Exception e) {
      log.warn("Could not write teams_notification_log: {}", e.getMessage());
    }
  }
}
