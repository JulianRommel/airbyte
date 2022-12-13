package io.airbyte.server.auth;

import io.micronaut.http.HttpRequest;
import io.micronaut.security.authentication.AuthenticationProvider;
import io.micronaut.security.authentication.AuthenticationRequest;
import io.micronaut.security.authentication.AuthenticationResponse;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

@Singleton
@Slf4j
@SuppressWarnings({"PMD.UnusedPrivateField"})
public class AirbyteAuthenticationProvider implements AuthenticationProvider {

  @Override
  public Publisher<AuthenticationResponse> authenticate(final HttpRequest<?> httpRequest, final AuthenticationRequest<?, ?> authenticationRequest) {
    log.info("Authenticating identity {}...", authenticationRequest.getIdentity());

    final String username = (String)authenticationRequest.getIdentity();
    final AuthenticationResponse authenticationResponse = AuthenticationResponse.success(username, PermissionService.ROLES);

    return Flux.create(emitter -> {
      emitter.next(authenticationResponse);
      emitter.complete();
    });
  }
}