package com.dodaso.ecosystem.common.controller;

import com.dodaso.ecosystem.common.service.UserMessagingIdentifierService;
import com.dodaso.ecosystem.ecws.container.UserMessagingIdentifierDTOContainer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/userMessagingIdentifierController")
@Slf4j
public class UserMessagingIdentifierController {

  @Autowired
  private UserMessagingIdentifierService identifierService;

  /**
   * Find all identifiers for a user
   */
  @PostMapping("/findByLoginId")
  public ResponseEntity<UserMessagingIdentifierDTOContainer> findByLoginId(
      @RequestBody UserMessagingIdentifierDTOContainer container) {
    try {
      container = identifierService.findByLoginId(container);
      return ResponseEntity.ok(container);
    } catch (Exception e) {
      log.error("Error in findByLoginId", e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Find identifier by user and channel type
   */
  @PostMapping("/findByLoginIdAndChannelType")
  public ResponseEntity<UserMessagingIdentifierDTOContainer> findByLoginIdAndChannelType(
      @RequestBody UserMessagingIdentifierDTOContainer container) {
    try {
      container = identifierService.findByLoginIdAndChannelType(container);
      return ResponseEntity.ok(container);
    } catch (Exception e) {
      log.error("Error in findByLoginIdAndChannelType", e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Save or update a user messaging identifier
   */
  @PostMapping("/save")
  public ResponseEntity<UserMessagingIdentifierDTOContainer> save(
      @RequestBody UserMessagingIdentifierDTOContainer container) {
    try {
      container = identifierService.save(container);
      return ResponseEntity.ok(container);
    } catch (Exception e) {
      log.error("Error in save", e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Save all identifiers for a user
   */
  @PostMapping("/saveAll")
  public ResponseEntity<UserMessagingIdentifierDTOContainer> saveAll(
      @RequestBody UserMessagingIdentifierDTOContainer container) {
    try {
      container = identifierService.saveAll(container);
      return ResponseEntity.ok(container);
    } catch (Exception e) {
      log.error("Error in saveAll", e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Delete a messaging identifier
   */
  @PostMapping("/delete")
  public ResponseEntity<Void> delete(
      @RequestBody UserMessagingIdentifierDTOContainer container) {
    try {
      identifierService.delete(container);
      return ResponseEntity.ok().build();
    } catch (Exception e) {
      log.error("Error in delete", e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Delete identifier by user and channel type
   */
  @PostMapping("/deleteByLoginIdAndChannelType")
  public ResponseEntity<Void> deleteByLoginIdAndChannelType(
      @RequestBody UserMessagingIdentifierDTOContainer container) {
    try {
      identifierService.deleteByLoginIdAndChannelType(container);
      return ResponseEntity.ok().build();
    } catch (Exception e) {
      log.error("Error in deleteByLoginIdAndChannelType", e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Delete all identifiers for a user
   */
  @PostMapping("/deleteAllByLoginId")
  public ResponseEntity<Void> deleteAllByLoginId(
      @RequestBody UserMessagingIdentifierDTOContainer container) {
    try {
      identifierService.deleteAllByLoginId(container);
      return ResponseEntity.ok().build();
    } catch (Exception e) {
      log.error("Error in deleteAllByLoginId", e);
      return ResponseEntity.internalServerError().build();
    }
  }
}