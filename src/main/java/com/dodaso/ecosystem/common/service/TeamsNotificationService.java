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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
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
   * Posts the card(s) in the request and writes one log row either way. A request without a card
   * only writes the log row (status and detail as given).
   *
   * <p>ADDED 2026-11-08. Delivery (request.delivery): "channel" (default) posts payload to the
   * shared channel webhook as before; "direct" posts directPayload to the direct webhook, whose
   * flow sends it to recipientAddress as a private chat, and falls back to the channel when a
   * direct message is not possible; "both" does both.
   */
  public SendResultDTO post(final TeamsNotifyDTO request) {
    String status = StringUtils.defaultIfBlank(request.getStatus(), "FAILED");
    String detail = request.getDetail();
    final boolean hasChannelCard = StringUtils.isNotBlank(request.getPayload());
    final boolean hasDirectCard = StringUtils.isNotBlank(request.getDirectPayload());
    log.info("Teams post received: key={}, type={}, task={}, recipient={}, delivery={}, card={} chars,"
        + " directCard={} chars, status given={}", request.getEventKey(), request.getEventType(),
        request.getTaskId(), request.getRecipientLogin(), request.getDelivery(),
        hasChannelCard ? request.getPayload().length() : 0,
        hasDirectCard ? request.getDirectPayload().length() : 0, request.getStatus());
    if (hasChannelCard || hasDirectCard) {
      final Route route = route(request.getDelivery(), hasDirectCard,
          StringUtils.isNotBlank(request.getRecipientAddress()),
          StringUtils.isNotBlank(request.getDirectWebhookOverride()));
      log.info("Teams post {}: route channel={}, direct={}{}", request.getEventKey(),
          route.channel(), route.direct(), route.note() == null ? "" : " (" + route.note() + ")");
      final List<Outcome> outcomes = new ArrayList<>();
      if (route.direct()) {
        outcomes.add(send("direct", request.getDirectWebhookOverride(), request.getDirectPayload(),
            request));
      }
      if (route.channel() && hasChannelCard) {
        final String label = route.fallback() ? "channel, fallback" : "channel";
        outcomes.add(send(label, webhookUrl(request.getWebhookOverride()), request.getPayload(),
            request));
      }
      if (!outcomes.isEmpty()) {
        final boolean allSent = outcomes.stream().allMatch(o -> "SENT".equals(o.status()));
        final boolean anySkipped = outcomes.stream().anyMatch(o -> "SKIPPED".equals(o.status()));
        status = allSent ? "SENT" : anySkipped && outcomes.size() == 1 ? "SKIPPED" : "FAILED";
        detail = outcomes.stream().map(o -> o.label() + ": " + o.detail())
            .collect(Collectors.joining("; "));
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

  /** Where one card goes. note is set when a direct message was wanted but is not possible; fallback when the channel then stands in for it. */
  record Route(boolean channel, boolean direct, String note, boolean fallback) {
  }

  private record Outcome(String label, String status, String detail) {
  }

  /**
   * Decides the route. Unknown or blank delivery means the shared channel, the behaviour before
   * direct messages existed.
   */
  static Route route(final String delivery, final boolean hasDirectCard,
      final boolean hasRecipientAddress, final boolean hasDirectWebhook) {
    final String mode = StringUtils.defaultIfBlank(delivery, "channel").trim().toLowerCase(Locale.ROOT);
    final boolean wantDirect = "direct".equals(mode) || "both".equals(mode);
    String why = null;
    if (wantDirect && !hasDirectCard) {
      why = "no direct card built";
    } else if (wantDirect && !hasRecipientAddress) {
      why = "no Teams address mapped for the recipient";
    } else if (wantDirect && !hasDirectWebhook) {
      why = "no direct webhook address (ecws.teams-events.direct-webhook-url)";
    }
    final boolean direct = wantDirect && why == null;
    final boolean fallback = "direct".equals(mode) && !direct;
    final boolean channel = !"direct".equals(mode) || fallback;
    return new Route(channel, direct, wantDirect && !direct ? why : null, fallback);
  }

  /** One webhook call, logged. Never throws. */
  private Outcome send(final String label, final String webhook, final String payload,
      final TeamsNotifyDTO request) {
    final long started = System.nanoTime();
    long idleSeconds = -1;
    try {
      if (StringUtils.isBlank(webhook)) {
        final String detail = "No Teams webhook address (ecws.teams-events.webhook-url or TEAMS row)";
        log.warn("Teams post {} ({}): {}", request.getEventKey(), label, detail);
        return new Outcome(label, "SKIPPED", detail);
      }
      final long previous = lastWebhookCall.getAndSet(System.currentTimeMillis());
      idleSeconds = previous == 0 ? -1 : (System.currentTimeMillis() - previous) / 1000;
      // Host only: the rest of the address holds the secret
      log.info("Teams webhook call #{} ({}): key={}, host={}, idle since the last call: {} s"
          + " (-1 = first call since start-up)", webhookCalls.incrementAndGet(), label,
          request.getEventKey(), hostOf(webhook), idleSeconds);
      final HttpResponse<String> response = http.send(HttpRequest.newBuilder()
          .uri(URI.create(webhook))
          .timeout(Duration.ofSeconds(20))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
          .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      final long ms = (System.nanoTime() - started) / 1_000_000;
      if (response.statusCode() / 100 == 2) {
        log.info("Teams webhook answered ({}): key={}, HTTP {}, {} ms", label,
            request.getEventKey(), response.statusCode(), ms);
        return new Outcome(label, "SENT", "HTTP " + response.statusCode());
      }
      final String detail = "HTTP " + response.statusCode() + " "
          + StringUtils.abbreviate(response.body(), 200);
      log.warn("Teams webhook refused {} ({}, key={}, {} ms): {}", request.getEventType(), label,
          request.getEventKey(), ms, detail);
      return new Outcome(label, "FAILED", detail);
    } catch (final Exception e) {
      log.warn("Teams notification failed for {} ({}, key={}) after {} ms, idle before the call {} s,"
          + " {} / root cause: {}", request.getEventType(), label, request.getEventKey(),
          (System.nanoTime() - started) / 1_000_000, idleSeconds, e.getClass().getName(),
          rootCause(e), e);
      return new Outcome(label, "FAILED",
          StringUtils.abbreviate(e.getClass().getSimpleName() + ": " + e.getMessage(), 400));
    }
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
