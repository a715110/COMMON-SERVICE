package com.dodaso.ecosystem.common.controller;

import com.dodaso.ecosystem.common.service.TeamsNotificationService;
import com.dodaso.ecosystem.ecws.dto.SendResultDTO;
import com.dodaso.ecosystem.ecws.dto.TeamsNotifyDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADDED 2026-10-24. Calls used by ecws-service to push task events to Teams (see
 * TeamsNotificationService). Internal: the browser never calls these.
 */
@RestController
@RequestMapping("/teamsNotificationController")
@RequiredArgsConstructor
@Slf4j
public class TeamsNotificationController {

  private final TeamsNotificationService teamsNotificationService;

  /** Reads loginId of the request, answers with identifier. */
  @PostMapping("/recipient")
  public ResponseEntity<TeamsNotifyDTO> recipient(@RequestBody final TeamsNotifyDTO request) {
    return ResponseEntity.ok(teamsNotificationService.recipient(request.getLoginId()));
  }

  /** Reads eventKey of the request, answers with alreadySent. */
  @PostMapping("/alreadySent")
  public ResponseEntity<TeamsNotifyDTO> alreadySent(@RequestBody final TeamsNotifyDTO request) {
    return ResponseEntity.ok(teamsNotificationService.alreadySent(request.getEventKey()));
  }

  @PostMapping("/post")
  public ResponseEntity<SendResultDTO> post(@RequestBody final TeamsNotifyDTO request) {
    return ResponseEntity.ok(teamsNotificationService.post(request));
  }
}
