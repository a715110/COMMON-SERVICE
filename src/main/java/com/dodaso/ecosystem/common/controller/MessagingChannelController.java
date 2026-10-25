package com.dodaso.ecosystem.common.controller;

import com.dodaso.ecosystem.common.entity.SystemMessagingConfig;
import com.dodaso.ecosystem.common.service.MessagingChannelService;
import com.dodaso.ecosystem.ecws.container.DirectMessageDTOContainer;
import com.dodaso.ecosystem.ecws.dto.DirectMessageDTO;
import com.dodaso.ecosystem.ecws.dto.SendResultDTO;
import com.dodaso.ecosystem.ecws.dto.SystemMessagingConfigDTO;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for messaging channel information. Used by the frontend JavaScript to determine which
 * channel to use.
 */
@RestController
@RequestMapping("/messagingChannelController")
@Slf4j
public class MessagingChannelController {

  @Autowired
  private MessagingChannelService messagingChannelService;

  /**
   * Get the default messaging channel configuration. Returns minimal info needed by the frontend.
   * REVISED 2026-10-24: answers with the DTO and without secrets (client secret, SMTP password,
   * webhook address), because the caller only needs the channel type and display name.
   */
  @GetMapping("/defaultChannel")
  public ResponseEntity<SystemMessagingConfigDTO> getDefaultChannel() {
    try {
      SystemMessagingConfig config = messagingChannelService.getDefaultChannel();
      if (config == null) {
        // Return empty config or 204 No Content
        return ResponseEntity.noContent().build();
      }
      SystemMessagingConfigDTO dto = new SystemMessagingConfigDTO();
      BeanUtils.copyProperties(config, dto);
      dto.setConfigured(true);
      dto.setClientSecret(null);
      dto.setSmtpPassword(null);
      dto.setWebhookUrl(null);
      return ResponseEntity.ok(dto);
    } catch (Exception e) {
      log.error("Error fetching default channel", e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Get all active messaging channels. Returns minimal info needed by the frontend for channel
   * selection.
   */
  @GetMapping("/activeChannels")
  public ResponseEntity<List<Map<String, Object>>> getActiveChannels() {
    try {
      List<SystemMessagingConfig> configs = messagingChannelService.getActiveChannels();

      List<Map<String, Object>> response = configs.stream()
          .map(config -> {
            Map<String, Object> channelInfo = new HashMap<>();
            channelInfo.put("channelType", config.getChannelType());
            channelInfo.put("displayName", config.getDisplayName());
            channelInfo.put("priority", config.getPriority());
            return channelInfo;
          })
          .collect(Collectors.toList());

      return ResponseEntity.ok(response);

    } catch (Exception e) {
      log.error("Error fetching active channels", e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Check if messaging is available (at least one channel configured).
   */
  @GetMapping("/status")
  public ResponseEntity<Map<String, Object>> getMessagingStatus() {
    try {
      SystemMessagingConfig defaultChannel = messagingChannelService.getDefaultChannel();
      List<SystemMessagingConfig> activeChannels = messagingChannelService.getActiveChannels();

      Map<String, Object> response = new HashMap<>();
      response.put("available", defaultChannel != null);
      response.put("channelCount", activeChannels.size());

      if (defaultChannel != null) {
        response.put("defaultChannel", defaultChannel.getChannelType());
        response.put("defaultChannelName", defaultChannel.getDisplayName());
      }

      return ResponseEntity.ok(response);

    } catch (Exception e) {
      log.error("Error checking messaging status", e);
      return ResponseEntity.internalServerError().build();
    }
  }

  @PostMapping("/sendMessage")
  public ResponseEntity<DirectMessageDTOContainer> sendMessage(
      @RequestBody DirectMessageDTOContainer container) {
    try {
      DirectMessageDTO dto = container.getDirectMessageDTO();

      SendResultDTO result = messagingChannelService.sendMessage(
          dto.getSenderLoginId(),
          dto.getRecipientLoginId(),
          dto.getRecipientEmail(),
          dto.getMessage(),
          dto.getChannelType(),
          dto.getAccessToken()
      );

      container.setSendResult(result);
      return ResponseEntity.ok(container);

    } catch (Exception e) {
      log.error("Error sending message", e);
      container.setSendResult(SendResultDTO.failure("Server error: " + e.getMessage()));
      return ResponseEntity.internalServerError().body(container);
    }
  }
}