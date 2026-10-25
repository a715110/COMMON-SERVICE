package com.dodaso.ecosystem.common.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dodaso.ecosystem.common.entity.UserMessagingIdentifier;
import com.dodaso.ecosystem.common.repository.UserMessagingIdentifierRepository;
import com.dodaso.ecosystem.common.security.VerifiedCaller;
import com.dodaso.ecosystem.ecws.container.UserMessagingIdentifierDTOContainer;
import com.dodaso.ecosystem.ecws.dto.UserMessagingIdentifierDTO;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** A verified caller changes and deletes only their own messaging identifiers. */
class UserMessagingIdentifierServiceTest {

  private final UserMessagingIdentifierRepository repository =
      mock(UserMessagingIdentifierRepository.class);
  private final UserMessagingIdentifierService service = new UserMessagingIdentifierService();

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(service, "identifierRepository", repository);
    UserMessagingIdentifier kims = new UserMessagingIdentifier();
    ReflectionTestUtils.setField(kims, "loginId", "kim@dodaso.com");
    when(repository.findById(7)).thenReturn(Optional.of(kims));
    when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
  }

  @AfterEach
  void clear() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void ownerDeletesTheirIdentifier() throws Exception {
    signIn("KIM@dodaso.com");
    service.delete(byId(7));
    verify(repository).deleteById(7);
  }

  @Test
  void someoneElsesIdentifierLooksMissing() {
    signIn("lee@dodaso.com");
    assertThatThrownBy(() -> service.delete(byId(7))).hasMessageContaining("not found");
    verify(repository, never()).deleteById(any());
  }

  @Test
  void unknownIdIsNotFound() {
    signIn("kim@dodaso.com");
    when(repository.findById(8)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.delete(byId(8))).hasMessageContaining("not found");
  }

  @Test
  void withoutATokenTheOldBehaviourStays() throws Exception {
    service.delete(byId(7));
    verify(repository).deleteById(7);
  }

  @Test
  void saveIsForTheCallerNotTheNamedLogin() throws Exception {
    signIn("lee@dodaso.com");
    UserMessagingIdentifierDTOContainer saved = service.save(forLogin("kim@dodaso.com"));
    assertThat(saved.getUserMessagingIdentifierDTO().getLoginId()).isEqualTo("lee@dodaso.com");
    verify(repository).findByLoginIdAndChannelType("lee@dodaso.com", "TEAMS");
  }

  @Test
  void deleteByChannelIsForTheCaller() throws Exception {
    signIn("lee@dodaso.com");
    service.deleteByLoginIdAndChannelType(forLogin("kim@dodaso.com"));
    verify(repository).deleteByLoginIdAndChannelType("lee@dodaso.com", "TEAMS");
  }

  @Test
  void deleteAllIsForTheCaller() throws Exception {
    signIn("lee@dodaso.com");
    UserMessagingIdentifierDTOContainer container = new UserMessagingIdentifierDTOContainer();
    container.setLoginId("kim@dodaso.com");
    service.deleteAllByLoginId(container);
    verify(repository).deleteByLoginId("lee@dodaso.com");
  }

  @Test
  void anyoneMayReadAnotherUsersIdentifiers() throws Exception {
    signIn("lee@dodaso.com");
    UserMessagingIdentifierDTOContainer container = new UserMessagingIdentifierDTOContainer();
    container.setLoginId("kim@dodaso.com");
    service.findByLoginId(container);
    verify(repository).findByLoginId("kim@dodaso.com");
  }

  private static UserMessagingIdentifierDTOContainer byId(int id) {
    UserMessagingIdentifierDTOContainer container = new UserMessagingIdentifierDTOContainer();
    UserMessagingIdentifierDTO dto = new UserMessagingIdentifierDTO();
    dto.setId(id);
    container.setUserMessagingIdentifierDTO(dto);
    return container;
  }

  private static UserMessagingIdentifierDTOContainer forLogin(String login) {
    UserMessagingIdentifierDTOContainer container = new UserMessagingIdentifierDTOContainer();
    UserMessagingIdentifierDTO dto = new UserMessagingIdentifierDTO();
    dto.setLoginId(login);
    dto.setChannelType("TEAMS");
    dto.setIdentifier("someone@contoso.com");
    container.setUserMessagingIdentifierDTO(dto);
    return container;
  }

  private static void signIn(String login) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(VerifiedCaller.ATTRIBUTE, new VerifiedCaller(login, List.of()));
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }
}
