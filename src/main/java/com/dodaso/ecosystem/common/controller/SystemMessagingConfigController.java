package com.dodaso.ecosystem.common.controller;

import com.dodaso.ecosystem.common.service.SystemMessagingConfigService;
import com.dodaso.ecosystem.ecws.container.SystemMessagingConfigDTOContainer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/systemMessagingConfigController")
@Slf4j
public class SystemMessagingConfigController {

  @Autowired
  private SystemMessagingConfigService configService;

  /**
   * Find all messaging configurations
   */
  @GetMapping("/findAll")
  public ResponseEntity<SystemMessagingConfigDTOContainer> findAll() {
    try {
      SystemMessagingConfigDTOContainer container = new SystemMessagingConfigDTOContainer();
      container = configService.findAll(container);
      return ResponseEntity.ok(container);
    } catch (Exception e) {
      log.error("Error in findAll", e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Find all active messaging configurations
   */
  @GetMapping("/findAllActive")
  public ResponseEntity<SystemMessagingConfigDTOContainer> findAllActive() {
    try {
      SystemMessagingConfigDTOContainer container = new SystemMessagingConfigDTOContainer();
      container = configService.findAllActive(container);
      return ResponseEntity.ok(container);
    } catch (Exception e) {
      log.error("Error in findAllActive", e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Find configuration by channel type
   */
  @PostMapping("/findByChannelType")
  public ResponseEntity<SystemMessagingConfigDTOContainer> findByChannelType(
      @RequestBody SystemMessagingConfigDTOContainer container) {
    try {
      container = configService.findByChannelType(container);
      return ResponseEntity.ok(container);
    } catch (Exception e) {
      log.error("Error in findByChannelType", e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Find configuration by ID
   */
  @PostMapping("/findById")
  public ResponseEntity<SystemMessagingConfigDTOContainer> findById(
      @RequestBody SystemMessagingConfigDTOContainer container) {
    try {
      container = configService.findById(container);
      return ResponseEntity.ok(container);
    } catch (Exception e) {
      log.error("Error in findById", e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Save or update a messaging configuration
   */
  @PostMapping("/save")
  public ResponseEntity<SystemMessagingConfigDTOContainer> save(
      @RequestBody SystemMessagingConfigDTOContainer container) {
    try {
      container = configService.save(container);
      return ResponseEntity.ok(container);
    } catch (Exception e) {
      log.error("Error in save", e);
      SystemMessagingConfigDTOContainer errorContainer = new SystemMessagingConfigDTOContainer();
      errorContainer.setResultMessage("Error saving configuration: " + e.getMessage());
      return ResponseEntity.badRequest().body(errorContainer);
    }
  }

  /**
   * Save all configurations (batch update)
   */
  @PostMapping("/saveAll")
  public ResponseEntity<SystemMessagingConfigDTOContainer> saveAll(
      @RequestBody SystemMessagingConfigDTOContainer container) {
    try {
      container = configService.saveAll(container);
      return ResponseEntity.ok(container);
    } catch (Exception e) {
      log.error("Error in saveAll", e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Delete a messaging configuration
   */
  @PostMapping("/delete")
  public ResponseEntity<Void> delete(
      @RequestBody SystemMessagingConfigDTOContainer container) {
    try {
      configService.delete(container);
      return ResponseEntity.ok().build();
    } catch (Exception e) {
      log.error("Error in delete", e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Toggle active status
   */
  @PostMapping("/toggleActive")
  public ResponseEntity<SystemMessagingConfigDTOContainer> toggleActive(
      @RequestBody SystemMessagingConfigDTOContainer container) {
    try {
      container = configService.toggleActive(container);
      return ResponseEntity.ok(container);
    } catch (Exception e) {
      log.error("Error in toggleActive", e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Test connection for a messaging configuration
   */
  @PostMapping("/testConnection")
  public ResponseEntity<SystemMessagingConfigDTOContainer> testConnection(
      @RequestBody SystemMessagingConfigDTOContainer container) {
    try {
      container = configService.testConnection(container);
      return ResponseEntity.ok(container);
    } catch (Exception e) {
      log.error("Error in testConnection", e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Initialize default configurations
   */
  @PostMapping("/initializeDefaults")
  public ResponseEntity<SystemMessagingConfigDTOContainer> initializeDefaults(
      @RequestBody SystemMessagingConfigDTOContainer container) {
    try {
      container = configService.initializeDefaults(container);
      return ResponseEntity.ok(container);
    } catch (Exception e) {
      log.error("Error in initializeDefaults", e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Reset all configurations to defaults
   */
  @PostMapping("/resetToDefaults")
  public ResponseEntity<SystemMessagingConfigDTOContainer> resetToDefaults(
      @RequestBody SystemMessagingConfigDTOContainer container) {
    try {
      container = configService.resetToDefaults(container);
      return ResponseEntity.ok(container);
    } catch (Exception e) {
      log.error("Error in resetToDefaults", e);
      return ResponseEntity.internalServerError().build();
    }
  }
}