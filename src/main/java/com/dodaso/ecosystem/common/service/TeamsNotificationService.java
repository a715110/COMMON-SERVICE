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
import java.util.concurrent.atomic.AtomicLong;
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

  /** ADDED 2026-11-07. When the webhook was last called (epoch ms, 0 = not since start-up). */
  private final AtomicLong lastWebhookCall = new AtomicLong();
  private final AtomicLong webhookCalls = new AtomicLong();

  /** Teams address of the login, or null when none is mapped. */
  public TeamsNotifyDTO recipient(final String loginId) {
    final TeamsNotifyDTO reply = new TeamsNotifyDTO();
    reply.setLoginId(loginId);
    if (StringUtils.isNotBlank(loginId)) {
      reply.setIdentifier(identifierRepository.findByLoginIdAndChannelType(loginId, CHANNEL)
          .map(i -> i.getIdentifier()).orElse(null));
    }
    log.debug("Teams recipient lookup: login={}, mapped={}", loginId, reply.getIdentifier() != null);
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
    log.info("Teams post received: key={}, type={}, task={}, recipient={}, payload={} chars, status given={}",
        request.getEventKey(), request.getEventType(), request.getTaskId(), request.getRecipientLogin(),
        request.getPayload() == null ? 0 : request.getPayload().length(), request.getStatus());
    if (StringUtils.isNotBlank(request.getPayload())) {
      final long started = System.nanoTime();
      long idleSeconds = -1;
      try {
        final String webhook = webhookUrl(request.getWebhookOverride());
        if (StringUtils.isBlank(webhook)) {
          status = "SKIPPED";
          detail = "No Teams webhook address (ecws.teams-events.webhook-url or TEAMS row)";
          log.warn("Teams post {}: {}", request.getEventKey(), detail);
        } else {
          final long previous = lastWebhookCall.getAndSet(System.currentTimeMillis());
          idleSeconds = previous == 0 ? -1 : (System.currentTimeMillis() - previous) / 1000;
          // Host only: the rest of the address holds the secret
          log.info("Teams webhook call #{}: key={}, host={}, idle since the last call: {} s"
              + " (-1 = first call since start-up)", webhookCalls.incrementAndGet(),
              request.getEventKey(), hostOf(webhook), idleSeconds);
          final HttpResponse<String> response = http.send(HttpRequest.newBuilder()
              .uri(URI.create(webhook))
              .timeout(Duration.ofSeconds(20))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(request.getPayload(),
                  StandardCharsets.UTF_8))
              .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
          final long ms = (System.nanoTime() - started) / 1_000_000;
          if (response.statusCode() / 100 == 2) {
            status = "SENT";
            detail = "HTTP " + response.statusCode();
            log.info("Teams webhook answered: key={}, HTTP {}, {} ms", request.getEventKey(),
                response.statusCode(), ms);
          } else {
            status = "FAILED";
            detail = "HTTP " + response.statusCode() + " "
                + StringUtils.abbreviate(response.body(), 200);
            log.warn("Teams webhook refused {} (key={}, {} ms): {}", request.getEventType(),
                request.getEventKey(), ms, detail);
          }
        }
      } catch (final Exception e) {
        status = "FAILED";
        detail = StringUtils.abbreviate(e.getClass().getSimpleName() + ": " + e.getMessage(), 480);
        log.warn("Teams notification failed for {} (key={}) after {} ms, idle before the call {} s,"
            + " {} / root cause: {}", request.getEventType(), request.getEventKey(),
            (System.nanoTime() - started) / 1_000_000, idleSeconds, e.getClass().getName(),
            rootCause(e), e);
      }
    } else {
      log.info("Teams post {}: no card in the request, only the log row is written ({})",
          request.getEventKey(), status);
    }
    record(request, status, detail);
    log.info("Teams post finished: key={}, status={}", request.getEventKey(), status);
    return "SENT".equals(status) ? SendResultDTO.success("Reminder sent to Teams")
        : SendResultDTO.failure(detail);
  }

  private static String hostOf(final String url) {
    try {
      return URI.create(url).getHost();
    } catch (final RuntimeException e) {
      return "(address not valid)";
    }
  }

  private static String rootCause(final Throwable e) {
    Throwable t = e;
    int guard = 0;
    while (t.getCause() != null && t.getCause() != t && guard++ < 10) {
      t = t.getCause();
    }
    return t.getClass().getName() + ": " + t.getMessage();
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
