package com.dodaso.ecosystem.common.service;

import com.dodaso.ecosystem.common.entity.SystemMessagingConfig;
import com.dodaso.ecosystem.common.entity.UserMessagingIdentifier;
import com.dodaso.ecosystem.common.repository.SystemMessagingConfigRepository;
import com.dodaso.ecosystem.common.repository.UserMessagingIdentifierRepository;
import com.dodaso.ecosystem.ecws.dto.SendResultDTO;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

/**
 * Service for sending messages through various configured channels.
 * Supports Microsoft Teams, Email, Slack, and other messaging platforms.
 */
@Service
@Slf4j
public class MessagingChannelService {

  @Autowired
  private SystemMessagingConfigRepository configRepository;

  @Autowired
  private UserMessagingIdentifierRepository identifierRepository;

  @Autowired
  private TeamsGraphClient teamsGraphClient;

  @Value("${app.backend.base-url:https://localhost:8443}")
  private String backendBaseUrl;

  // Cache for mail senders (avoid recreating for each message)
  private final Map<Integer, JavaMailSenderImpl> mailSenderCache = new ConcurrentHashMap<>();

  /**
   * Get the default (highest priority) active messaging channel
   */
  public SystemMessagingConfig getDefaultChannel() {
    List<SystemMessagingConfig> activeConfigs = configRepository.findByActiveIndTrueOrderByPriorityAsc();
    return activeConfigs.isEmpty() ? null : activeConfigs.get(0);
  }

  /**
   * Get all active messaging channels
   */
  public List<SystemMessagingConfig> getActiveChannels() {
    return configRepository.findByActiveIndTrueOrderByPriorityAsc();
  }

  /**
   * Get configuration for a specific channel type
   */
  public Optional<SystemMessagingConfig> getChannelConfig(String channelType) {
    return configRepository.findByChannelTypeAndActiveIndTrue(channelType);
  }

  /**
   * Get user's identifier for a specific channel
   */
  public String getUserIdentifier(String loginId, String channelType) {
    Optional<UserMessagingIdentifier> identifierOpt =
        identifierRepository.findByLoginIdAndChannelType(loginId, channelType);
    return identifierOpt.map(UserMessagingIdentifier::getIdentifier).orElse(null);
  }

  /**
   * Send a message using the default channel
   */
  public SendResultDTO sendMessage(String senderLoginId, String recipientLoginId,
      String recipientEmail, String message, String accessToken) {

    SystemMessagingConfig defaultChannel = getDefaultChannel();

    if (defaultChannel == null) {
      log.warn("No active messaging channel configured");
      return SendResultDTO.failure("No messaging channel configured. Please contact your administrator.");
    }

    return sendMessage(senderLoginId, recipientLoginId, recipientEmail, message,
        defaultChannel.getChannelType(), accessToken);
  }

  /**
   * Send a message using a specific channel
   */
  public SendResultDTO sendMessage(String senderLoginId, String recipientLoginId,
      String recipientEmail, String message, String channelType, String accessToken) {

    Optional<SystemMessagingConfig> configOpt = getChannelConfig(channelType);

    if (configOpt.isEmpty()) {
      log.warn("Channel {} is not configured or not active", channelType);
      return SendResultDTO.failure("Channel " + channelType + " is not available.");
    }

    SystemMessagingConfig config = configOpt.get();

    // Get the recipient's identifier for this channel
    String recipientIdentifier = getUserIdentifier(recipientLoginId, channelType);

    // Fallback to email for certain channels
    if (StringUtils.isBlank(recipientIdentifier)) {
      if ("EMAIL".equalsIgnoreCase(channelType)) {
        recipientIdentifier = recipientEmail;
      } else if ("TEAMS".equalsIgnoreCase(channelType)) {
        // Teams can use email/UPN as identifier
        recipientIdentifier = recipientEmail;
      }
    }

    if (StringUtils.isBlank(recipientIdentifier)) {
      log.warn("No identifier found for user {} on channel {}", recipientLoginId, channelType);
      return SendResultDTO.failure("Recipient not configured for " + config.getDisplayName());
    }

    // Route to appropriate handler
    switch (channelType.toUpperCase()) {
      case "TEAMS":
        return sendTeamsMessage(config, recipientIdentifier, message, accessToken);
      case "EMAIL":
        return sendEmailMessage(config, senderLoginId, recipientIdentifier, message);
      case "SLACK":
        return sendSlackMessage(config, recipientIdentifier, message);
      case "SKYPE":
        return sendSkypeMessage(config, recipientIdentifier, message, accessToken);
      default:
        log.warn("Unsupported channel type: {}", channelType);
        return SendResultDTO.failure("Unsupported channel: " + channelType);
    }
  }

  /**
   * Send message via Microsoft Teams
   */
  private SendResultDTO sendTeamsMessage(SystemMessagingConfig config, String recipientUpn,
      String message, String accessToken) {

    if (StringUtils.isBlank(accessToken)) {
      log.warn("No access token provided for Teams message");
      return SendResultDTO.needsLogin("Microsoft sign-in required");
    }

    // REVISED 2026-10-23: direct Microsoft Graph call as the signed-in person (see
    // TeamsGraphClient). It used to post to a separate backend at app.backend.base-url
    // /api/teams/dm, which does not exist in this deployment.
    final SendResultDTO result = teamsGraphClient.send(accessToken, recipientUpn, message);
    result.setChannelType("TEAMS");
    return result;
  }

  /**
   * Send message via Email
   */
  private SendResultDTO sendEmailMessage(SystemMessagingConfig config, String senderLoginId,
      String recipientEmail, String message) {

    if (StringUtils.isBlank(config.getSmtpHost()) || config.getSmtpPort() == null) {
      log.warn("Email not properly configured");
      return SendResultDTO.failure("Email not configured properly");
    }

    try {
      JavaMailSenderImpl mailSender = getOrCreateMailSender(config);

      SimpleMailMessage mailMessage = new SimpleMailMessage();
      mailMessage.setFrom(config.getEmailFromAddress());
      mailMessage.setTo(recipientEmail);
      mailMessage.setSubject("ECWS Message from " + senderLoginId);
      mailMessage.setText(message);

      mailSender.send(mailMessage);

      log.info("Email sent successfully to {}", recipientEmail);
      return SendResultDTO.success("Message sent via Email");

    } catch (Exception e) {
      log.error("Error sending email", e);
      return SendResultDTO.failure("Email error: " + e.getMessage());
    }
  }

  /**
   * Send message via Slack
   */
  private SendResultDTO sendSlackMessage(SystemMessagingConfig config, String recipientSlackId,
      String message) {

    if (StringUtils.isBlank(config.getWebhookUrl())) {
      log.warn("Slack bot token not configured");
      return SendResultDTO.failure("Slack not configured properly");
    }

    try {
      // Slack API call using Bot Token
      String botToken = config.getWebhookUrl(); // We stored bot token in webhookUrl

      String payload = String.format(
          "{\"channel\":\"%s\",\"text\":\"%s\"}",
          escapeJson(recipientSlackId),
          escapeJson(message)
      );

      java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
          .connectTimeout(java.time.Duration.ofSeconds(10))
          .build();

      java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
          .uri(java.net.URI.create("https://slack.com/api/chat.postMessage"))
          .timeout(java.time.Duration.ofSeconds(20))
          .header("Content-Type", "application/json")
          .header("Authorization", "Bearer " + botToken)
          .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload, java.nio.charset.StandardCharsets.UTF_8))
          .build();

      java.net.http.HttpResponse<String> resp = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());

      if (resp.statusCode() == 200 && resp.body().contains("\"ok\":true")) {
        log.info("Slack message sent successfully to {}", recipientSlackId);
        return SendResultDTO.success("Message sent via Slack");
      } else {
        log.error("Slack API error: {}", resp.body());
        return SendResultDTO.failure("Slack send failed");
      }

    } catch (Exception e) {
      log.error("Error sending Slack message", e);
      return SendResultDTO.failure("Slack error: " + e.getMessage());
    }
  }

  /**
   * Send message via Skype (deprecated but supported)
   */
  private SendResultDTO sendSkypeMessage(SystemMessagingConfig config, String recipientSkypeId,
      String message, String accessToken) {

    // Skype for Business is being retired; this is a placeholder
    log.warn("Skype messaging is deprecated. Consider using Microsoft Teams.");
    return SendResultDTO.failure("Skype for Business is being retired. Please use Microsoft Teams instead.");
  }

  /**
   * Get or create a cached mail sender for the given config
   */
  private JavaMailSenderImpl getOrCreateMailSender(SystemMessagingConfig config) {
    return mailSenderCache.computeIfAbsent(config.getId(), id -> {
      JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
      mailSender.setHost(config.getSmtpHost());
      mailSender.setPort(config.getSmtpPort());

      if (StringUtils.isNotBlank(config.getSmtpUsername())) {
        mailSender.setUsername(config.getSmtpUsername());
        mailSender.setPassword(config.getSmtpPassword());
      }

      Properties props = mailSender.getJavaMailProperties();
      props.put("mail.transport.protocol", "smtp");
      props.put("mail.smtp.auth", StringUtils.isNotBlank(config.getSmtpUsername()) ? "true" : "false");
      props.put("mail.smtp.starttls.enable", Boolean.TRUE.equals(config.getSmtpUseTls()) ? "true" : "false");
      props.put("mail.smtp.connectiontimeout", "10000");
      props.put("mail.smtp.timeout", "10000");

      return mailSender;
    });
  }

  /**
   * Clear mail sender cache (call when config changes)
   */
  public void clearMailSenderCache() {
    mailSenderCache.clear();
  }

  private static String escapeJson(String s) {
    if (s == null) return "";
    return s.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "");
  }
}