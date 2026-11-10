package com.dodaso.ecosystem.common.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.dodaso.ecosystem.common.service.TeamsNotificationService.Route;
import org.junit.jupiter.api.Test;

/** Which webhook a Teams card goes to, for each delivery setting. */
class TeamsNotificationRouteTest {

  @Test
  void blankOrUnknownDeliveryIsTheChannelAsBefore() {
    for (String mode : new String[] {null, "", "channel", "nonsense"}) {
      Route r = TeamsNotificationService.route(mode, true, true, true);
      assertThat(r.channel()).as(mode).isTrue();
      assertThat(r.direct()).as(mode).isFalse();
      assertThat(r.note()).as(mode).isNull();
    }
  }

  @Test
  void directGoesOnlyToTheDirectWebhookWhenPossible() {
    Route r = TeamsNotificationService.route("direct", true, true, true);
    assertThat(r.direct()).isTrue();
    assertThat(r.channel()).isFalse();
    assertThat(r.fallback()).isFalse();
  }

  @Test
  void directFallsBackToTheChannelWhenNoAddressIsMapped() {
    Route r = TeamsNotificationService.route("direct", true, false, true);
    assertThat(r.direct()).isFalse();
    assertThat(r.channel()).isTrue();
    assertThat(r.fallback()).isTrue();
    assertThat(r.note()).contains("no Teams address");
  }

  @Test
  void directFallsBackToTheChannelWhenTheDirectWebhookIsNotSet() {
    Route r = TeamsNotificationService.route("Direct", true, true, false);
    assertThat(r.direct()).isFalse();
    assertThat(r.channel()).isTrue();
    assertThat(r.note()).contains("direct webhook");
  }

  @Test
  void bothSendsToTheChannelAndDirectly() {
    Route r = TeamsNotificationService.route("both", true, true, true);
    assertThat(r.direct()).isTrue();
    assertThat(r.channel()).isTrue();
    assertThat(r.fallback()).isFalse();
  }

  @Test
  void bothStillSendsToTheChannelWhenDirectIsNotPossible() {
    Route r = TeamsNotificationService.route("both", false, true, true);
    assertThat(r.direct()).isFalse();
    assertThat(r.channel()).isTrue();
    assertThat(r.fallback()).isFalse();
  }
}
