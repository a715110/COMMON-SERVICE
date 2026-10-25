package com.dodaso.ecosystem.common.service;

import com.dodaso.ecosystem.common.entity.UserMessagingIdentifier;
import com.dodaso.ecosystem.common.repository.UserMessagingIdentifierRepository;
import com.dodaso.ecosystem.common.security.CallerLogin;
import com.dodaso.ecosystem.ecws.container.UserMessagingIdentifierDTOContainer;
import com.dodaso.ecosystem.ecws.dto.UserMessagingIdentifierDTO;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@Slf4j
public class UserMessagingIdentifierService {

  @Autowired
  private UserMessagingIdentifierRepository identifierRepository;

  /**
   * Find all identifiers for a user
   */
  @Transactional(readOnly = true)
  public UserMessagingIdentifierDTOContainer findByLoginId(
      UserMessagingIdentifierDTOContainer container) throws Exception {

    String loginId = container.getLoginId();

    List<UserMessagingIdentifier> entities = identifierRepository.findByLoginId(loginId);

    List<UserMessagingIdentifierDTO> dtos = new ArrayList<>();
    for (UserMessagingIdentifier entity : entities) {
      dtos.add(convertToDTO(entity));
    }

    container.setUserMessagingIdentifierDTOS(dtos);
    log.debug("Found {} messaging identifiers for user {}", dtos.size(), loginId);
    return container;
  }

  /**
   * Find identifier by user and channel type
   */
  @Transactional(readOnly = true)
  public UserMessagingIdentifierDTOContainer findByLoginIdAndChannelType(
      UserMessagingIdentifierDTOContainer container) throws Exception {

    UserMessagingIdentifierDTO dto = container.getUserMessagingIdentifierDTO();
    String loginId = dto.getLoginId();
    String channelType = dto.getChannelType();

    Optional<UserMessagingIdentifier> entityOpt =
        identifierRepository.findByLoginIdAndChannelType(loginId, channelType);

    if (entityOpt.isPresent()) {
      container.setUserMessagingIdentifierDTO(convertToDTO(entityOpt.get()));
    } else {
      container.setUserMessagingIdentifierDTO(null);
    }

    return container;
  }

  /**
   * Save or update a user messaging identifier
   */
  public UserMessagingIdentifierDTOContainer save(
      UserMessagingIdentifierDTOContainer container) throws Exception {

    UserMessagingIdentifierDTO dto = container.getUserMessagingIdentifierDTO();
    // A verified caller saves only their own identifier
    dto.setLoginId(CallerLogin.actingFor(dto.getLoginId(), "save messaging identifier"));

    // Check if identifier already exists for this user and channel
    Optional<UserMessagingIdentifier> existingOpt =
        identifierRepository.findByLoginIdAndChannelType(dto.getLoginId(), dto.getChannelType());

    UserMessagingIdentifier entity;
    if (existingOpt.isPresent()) {
      // Update existing
      entity = existingOpt.get();
      entity.setIdentifier(dto.getIdentifier());
      entity.setVerifiedInd(dto.getVerifiedInd());
    } else {
      // Create new
      entity = convertToEntity(dto);
    }

    entity = identifierRepository.saveAndFlush(entity);

    container.setUserMessagingIdentifierDTO(convertToDTO(entity));
    log.info("Saved messaging identifier for user {} channel {}",
        dto.getLoginId(), dto.getChannelType());
    return container;
  }

  /**
   * Save all identifiers for a user
   */
  public UserMessagingIdentifierDTOContainer saveAll(
      UserMessagingIdentifierDTOContainer container) throws Exception {

    List<UserMessagingIdentifierDTO> dtos = container.getUserMessagingIdentifierDTOS();

    if (dtos == null || dtos.isEmpty()) {
      log.debug("No identifiers to save");
      return container;
    }

    List<UserMessagingIdentifierDTO> savedDtos = new ArrayList<>();

    for (UserMessagingIdentifierDTO dto : dtos) {
      // A verified caller saves only their own identifiers
      dto.setLoginId(CallerLogin.actingFor(dto.getLoginId(), "save messaging identifiers"));
      // Check if identifier already exists
      Optional<UserMessagingIdentifier> existingOpt =
          identifierRepository.findByLoginIdAndChannelType(dto.getLoginId(), dto.getChannelType());

      UserMessagingIdentifier entity;
      if (existingOpt.isPresent()) {
        entity = existingOpt.get();
        entity.setIdentifier(dto.getIdentifier());
        entity.setVerifiedInd(dto.getVerifiedInd());
      } else {
        entity = convertToEntity(dto);
      }

      entity = identifierRepository.saveAndFlush(entity);
      savedDtos.add(convertToDTO(entity));
    }

    container.setUserMessagingIdentifierDTOS(savedDtos);
    log.info("Saved {} messaging identifiers", savedDtos.size());
    return container;
  }

  /**
   * Delete a messaging identifier
   */
  public void delete(UserMessagingIdentifierDTOContainer container) throws Exception {
    Integer id = container.getUserMessagingIdentifierDTO().getId();

    // if (!identifierRepository.existsById(id)) {
    // Someone else's identifier looks like a missing one to a verified caller
    Optional<UserMessagingIdentifier> existing = identifierRepository.findById(id);
    if (existing.isEmpty() || !CallerLogin.mayActFor(existing.get().getLoginId())) {
      throw new Exception("Messaging identifier not found with id: " + id);
    }

    identifierRepository.deleteById(id);
    log.info("Deleted messaging identifier with id: {}", id);
  }

  /**
   * Delete identifier by user and channel type
   */
  public void deleteByLoginIdAndChannelType(
      UserMessagingIdentifierDTOContainer container) throws Exception {

    UserMessagingIdentifierDTO dto = container.getUserMessagingIdentifierDTO();
    // String loginId = dto.getLoginId();
    String loginId = CallerLogin.actingFor(dto.getLoginId(), "delete messaging identifier");
    String channelType = dto.getChannelType();

    identifierRepository.deleteByLoginIdAndChannelType(loginId, channelType);
    log.info("Deleted messaging identifier for user {} channel {}", loginId, channelType);
  }

  /**
   * Delete all identifiers for a user
   */
  public void deleteAllByLoginId(UserMessagingIdentifierDTOContainer container) throws Exception {
    // String loginId = container.getLoginId();
    String loginId = CallerLogin.actingFor(container.getLoginId(), "delete messaging identifiers");

    identifierRepository.deleteByLoginId(loginId);
    log.info("Deleted all messaging identifiers for user {}", loginId);
  }

  // ========== Conversion Methods ==========

  private UserMessagingIdentifierDTO convertToDTO(UserMessagingIdentifier entity) {
    UserMessagingIdentifierDTO dto = new UserMessagingIdentifierDTO();
    BeanUtils.copyProperties(entity, dto);
    return dto;
  }

  private UserMessagingIdentifier convertToEntity(UserMessagingIdentifierDTO dto) {
    UserMessagingIdentifier entity = new UserMessagingIdentifier();
    BeanUtils.copyProperties(dto, entity, "id", "createdAt", "createdBy");
    return entity;
  }
}