package com.dodaso.ecosystem.common.service;

import com.dodaso.ecosystem.common.entity.SystemMessagingConfig;
import com.dodaso.ecosystem.common.repository.SystemMessagingConfigRepository;
import com.dodaso.ecosystem.ecws.container.SystemMessagingConfigDTOContainer;
import com.dodaso.ecosystem.ecws.dto.SystemMessagingConfigDTO;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@Slf4j
public class SystemMessagingConfigService {

  @Autowired
  private SystemMessagingConfigRepository configRepository;

  /**
   * Find all messaging configurations
   */
  @Transactional(readOnly = true)
  public SystemMessagingConfigDTOContainer findAll(
      SystemMessagingConfigDTOContainer container) throws Exception {

    List<SystemMessagingConfig> entities = configRepository.findAllByOrderByPriorityAsc();

    List<SystemMessagingConfigDTO> dtos = new ArrayList<>();
    for (SystemMessagingConfig entity : entities) {
      dtos.add(convertToDTO(entity));
    }

    container.setSystemMessagingConfigDTOS(dtos);
    log.debug("Found {} messaging configurations", dtos.size());
    return container;
  }

  /**
   * Find all active/enabled messaging configurations
   */
  @Transactional(readOnly = true)
  public SystemMessagingConfigDTOContainer findAllActive(
      SystemMessagingConfigDTOContainer container) throws Exception {

    List<SystemMessagingConfig> entities = configRepository.findByActiveIndTrueOrderByPriorityAsc();

    List<SystemMessagingConfigDTO> dtos = new ArrayList<>();
    for (SystemMessagingConfig entity : entities) {
      dtos.add(convertToDTO(entity));
    }

    container.setSystemMessagingConfigDTOS(dtos);
    log.debug("Found {} active messaging configurations", dtos.size());
    return container;
  }

  /**
   * Find configuration by channel type
   */
  @Transactional(readOnly = true)
  public SystemMessagingConfigDTOContainer findByChannelType(
      SystemMessagingConfigDTOContainer container) throws Exception {

    String channelType = container.getSystemMessagingConfigDTO().getChannelType();

    Optional<SystemMessagingConfig> entityOpt = configRepository.findByChannelType(channelType);

    if (entityOpt.isPresent()) {
      container.setSystemMessagingConfigDTO(convertToDTO(entityOpt.get()));
    } else {
      container.setSystemMessagingConfigDTO(null);
    }

    return container;
  }

  /**
   * Find configuration by ID
   */
  @Transactional(readOnly = true)
  public SystemMessagingConfigDTOContainer findById(
      SystemMessagingConfigDTOContainer container) throws Exception {

    Integer id = container.getSystemMessagingConfigDTO().getId();

    Optional<SystemMessagingConfig> entityOpt = configRepository.findById(id);

    if (entityOpt.isPresent()) {
      container.setSystemMessagingConfigDTO(convertToDTO(entityOpt.get()));
    } else {
      throw new Exception("Messaging configuration not found with id: " + id);
    }

    return container;
  }

  /**
   * Save or update a messaging configuration
   */
  public SystemMessagingConfigDTOContainer save(
      SystemMessagingConfigDTOContainer container) throws Exception {

    SystemMessagingConfigDTO dto = container.getSystemMessagingConfigDTO();

    // Check for duplicate channel type on new records
    if (dto.getId() == null || dto.getId() == 0) {
      if (configRepository.existsByChannelType(dto.getChannelType())) {
        throw new Exception("Configuration for channel type '" + dto.getChannelType() + "' already exists.");
      }

      // Set default priority if not provided
      if (dto.getPriority() == null) {
        dto.setPriority(configRepository.findMaxPriority() + 1);
      }
    }

    SystemMessagingConfig entity = convertToEntity(dto);
    entity = configRepository.saveAndFlush(entity);

    container.setSystemMessagingConfigDTO(convertToDTO(entity));
    log.info("Saved messaging configuration: {} ({})", entity.getDisplayName(), entity.getChannelType());
    return container;
  }

  /**
   * Save all configurations (batch update)
   */
  public SystemMessagingConfigDTOContainer saveAll(
      SystemMessagingConfigDTOContainer container) throws Exception {

    List<SystemMessagingConfigDTO> dtos = container.getSystemMessagingConfigDTOS();

    if (dtos == null || dtos.isEmpty()) {
      log.debug("No configurations to save");
      return container;
    }

    List<SystemMessagingConfigDTO> savedDtos = new ArrayList<>();

    for (SystemMessagingConfigDTO dto : dtos) {
      SystemMessagingConfig entity = convertToEntity(dto);
      entity = configRepository.saveAndFlush(entity);
      savedDtos.add(convertToDTO(entity));
    }

    container.setSystemMessagingConfigDTOS(savedDtos);
    log.info("Saved {} messaging configurations", savedDtos.size());
    return container;
  }

  /**
   * Delete a messaging configuration
   */
  public void delete(SystemMessagingConfigDTOContainer container) throws Exception {
    Integer id = container.getSystemMessagingConfigDTO().getId();

    if (!configRepository.existsById(id)) {
      throw new Exception("Messaging configuration not found with id: " + id);
    }

    configRepository.deleteById(id);
    log.info("Deleted messaging configuration with id: {}", id);
  }

  /**
   * Toggle active status
   */
  public SystemMessagingConfigDTOContainer toggleActive(
      SystemMessagingConfigDTOContainer container) throws Exception {

    Integer id = container.getSystemMessagingConfigDTO().getId();

    SystemMessagingConfig entity = configRepository.findById(id)
        .orElseThrow(() -> new Exception("Messaging configuration not found with id: " + id));

    entity.setActiveInd(!Boolean.TRUE.equals(entity.getActiveInd()));
    entity = configRepository.saveAndFlush(entity);

    container.setSystemMessagingConfigDTO(convertToDTO(entity));
    log.info("Toggled active status for {}: {}", entity.getChannelType(), entity.getActiveInd());
    return container;
  }

  /**
   * Test connection for a messaging configuration
   */
  public SystemMessagingConfigDTOContainer testConnection(
      SystemMessagingConfigDTOContainer container) throws Exception {

    SystemMessagingConfigDTO dto = container.getSystemMessagingConfigDTO();
    boolean success = false;
    String message = "";

    try {
      switch (dto.getChannelType().toUpperCase()) {
        case "EMAIL":
          success = testEmailConnection(dto);
          message = success ? "SMTP connection successful" : "SMTP connection failed";
          break;

        case "TEAMS":
          success = testTeamsConnection(dto);
          message = success ? "Teams configuration validated" : "Teams configuration incomplete";
          break;

        case "SLACK":
          success = testSlackConnection(dto);
          message = success ? "Slack configuration validated" : "Slack configuration incomplete";
          break;

        case "SKYPE":
          success = testSkypeConnection(dto);
          message = success ? "Skype configuration validated" : "Skype configuration incomplete";
          break;

        default:
          message = "Unknown channel type: " + dto.getChannelType();
      }

      // Store test result in additionalSettings
      if (dto.getAdditionalSettings() == null) {
        dto.setAdditionalSettings(new java.util.HashMap<>());
      }
      dto.getAdditionalSettings().put("lastTestResult", success);
      dto.getAdditionalSettings().put("lastTestMessage", message);
      dto.getAdditionalSettings().put("lastTestTime", java.time.Instant.now().toString());

      container.setSystemMessagingConfigDTO(dto);
      container.setResultMessage(message);

      log.info("Connection test for {}: {} - {}", dto.getChannelType(), success, message);

    } catch (Exception e) {
      log.error("Connection test failed for {}", dto.getChannelType(), e);
      container.setResultMessage("Connection test failed: " + e.getMessage());
    }

    return container;
  }

  /**
   * Test SMTP email connection
   */
  private boolean testEmailConnection(SystemMessagingConfigDTO dto) {
    if (StringUtils.isBlank(dto.getSmtpHost()) || dto.getSmtpPort() == null) {
      return false;
    }

    try {
      JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
      mailSender.setHost(dto.getSmtpHost());
      mailSender.setPort(dto.getSmtpPort());

      if (StringUtils.isNotBlank(dto.getSmtpUsername())) {
        mailSender.setUsername(dto.getSmtpUsername());
        mailSender.setPassword(dto.getSmtpPassword());
      }

      Properties props = mailSender.getJavaMailProperties();
      props.put("mail.transport.protocol", "smtp");
      props.put("mail.smtp.auth", StringUtils.isNotBlank(dto.getSmtpUsername()) ? "true" : "false");
      props.put("mail.smtp.starttls.enable", Boolean.TRUE.equals(dto.getSmtpUseTls()) ? "true" : "false");
      props.put("mail.smtp.connectiontimeout", "5000");
      props.put("mail.smtp.timeout", "5000");

      // Test connection
      mailSender.testConnection();
      return true;

    } catch (Exception e) {
      log.error("SMTP connection test failed", e);
      return false;
    }
  }

  /**
   * Test Microsoft Teams configuration
   */
  private boolean testTeamsConnection(SystemMessagingConfigDTO dto) {
    // Validate required fields are present
    return StringUtils.isNotBlank(dto.getTenantId()) &&
        StringUtils.isNotBlank(dto.getClientId());
    // Note: Full OAuth validation would require actual API call
  }

  /**
   * Test Slack configuration
   */
  private boolean testSlackConnection(SystemMessagingConfigDTO dto) {
    // Validate required fields are present
    return StringUtils.isNotBlank(dto.getTenantId()) &&  // Workspace ID
        StringUtils.isNotBlank(dto.getClientId()) &&
        StringUtils.isNotBlank(dto.getWebhookUrl());      // Bot token
    // Note: Full validation would require actual API call
  }

  /**
   * Test Skype configuration
   */
  private boolean testSkypeConnection(SystemMessagingConfigDTO dto) {
    // Validate required fields are present
    return StringUtils.isNotBlank(dto.getClientId());
  }

  /**
   * Initialize default configurations for all channel types
   */
  public SystemMessagingConfigDTOContainer initializeDefaults(
      SystemMessagingConfigDTOContainer container) throws Exception {

    String[] channelTypes = {"TEAMS", "SKYPE", "SLACK", "ZOOM", "EMAIL"};
    String[] displayNames = {"Microsoft Teams", "Skype", "Slack", "Zoom", "Email"};

    List<SystemMessagingConfigDTO> savedDtos = new ArrayList<>();
    int priority = 1;

    for (int i = 0; i < channelTypes.length; i++) {
      String channelType = channelTypes[i];

      // Skip if already exists
      if (configRepository.existsByChannelType(channelType)) {
        log.debug("Configuration for {} already exists, skipping", channelType);
        continue;
      }

      SystemMessagingConfig entity = new SystemMessagingConfig();
      entity.setChannelType(channelType);
      entity.setDisplayName(displayNames[i]);
      entity.setPriority(priority++);
      entity.setActiveInd("EMAIL".equals(channelType)); // Only email active by default
      entity.setSmtpUseTls(true);

      entity = configRepository.saveAndFlush(entity);
      savedDtos.add(convertToDTO(entity));

      log.info("Initialized default configuration for: {}", channelType);
    }

    container.setSystemMessagingConfigDTOS(savedDtos);
    return container;
  }

  /**
   * Reset all configurations to defaults
   */
  public SystemMessagingConfigDTOContainer resetToDefaults(
      SystemMessagingConfigDTOContainer container) throws Exception {

    // Delete all existing configurations
    configRepository.deleteAll();
    log.info("Deleted all existing messaging configurations");

    // Re-initialize defaults
    return initializeDefaults(container);
  }

  // ========== Conversion Methods ==========

  private SystemMessagingConfigDTO convertToDTO(SystemMessagingConfig entity) {
    SystemMessagingConfigDTO dto = new SystemMessagingConfigDTO();
    BeanUtils.copyProperties(entity, dto);
    return dto;
  }

  private SystemMessagingConfig convertToEntity(SystemMessagingConfigDTO dto) {
    SystemMessagingConfig entity;

    if (dto.getId() != null && dto.getId() > 0) {
      // Update existing entity
      entity = configRepository.findById(dto.getId()).orElse(new SystemMessagingConfig());
    } else {
      entity = new SystemMessagingConfig();
    }

    BeanUtils.copyProperties(dto, entity, "id", "createdAt", "createdBy");

    // Ensure ID is set for existing records
    if (dto.getId() != null && dto.getId() > 0) {
      entity.setId(dto.getId());
    }

    return entity;
  }
}