/*
 * Copyright 2002-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.security.test.web.reactive.server;

import java.util.Collection;
import java.util.Collections;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.reactive.result.method.annotation.OAuth2AuthorizedClientArgumentResolver;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.server.WebSessionServerOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.server.context.SecurityContextServerWebExchangeWebFilter;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOAuth2Login;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

@RunWith(MockitoJUnitRunner.class)
public class SecurityMockServerConfigurersOAuth2LoginTests extends AbstractMockServerConfigurersTests {
	private OAuth2LoginController controller = new OAuth2LoginController();

	@Mock
	private ReactiveClientRegistrationRepository clientRegistrationRepository;

	private WebTestClient client;

	@Before
	public void setup() {
		ServerOAuth2AuthorizedClientRepository authorizedClientRepository =
				new WebSessionServerOAuth2AuthorizedClientRepository();

		this.client = WebTestClient
				.bindToController(this.controller)
				.argumentResolvers(c -> c.addCustomResolver(
						new OAuth2AuthorizedClientArgumentResolver
								(this.clientRegistrationRepository, authorizedClientRepository)))
				.webFilter(new SecurityContextServerWebExchangeWebFilter())
				.apply(springSecurity())
				.configureClient()
				.defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
				.build();
	}

	@Test
	public void oauth2LoginWhenUsingDefaultsThenProducesDefaultAuthentication() {
		this.client.mutateWith(mockOAuth2Login())
				.get().uri("/token")
				.exchange()
				.expectStatus().isOk();

		OAuth2AuthenticationToken token = this.controller.token;
		assertThat(token).isNotNull();
		assertThat(token.getAuthorizedClientRegistrationId()).isEqualTo("test");
		assertThat(token.getPrincipal()).isInstanceOf(OAuth2User.class);
		assertThat(token.getPrincipal().getAttributes())
				.containsEntry("sub", "test-subject");
		assertThat((Collection<GrantedAuthority>) token.getPrincipal().getAuthorities())
				.contains(new SimpleGrantedAuthority("SCOPE_user"));
	}

	@Test
	public void oauth2LoginWhenUsingDefaultsThenProducesDefaultAuthorizedClient() {
		this.client.mutateWith(mockOAuth2Login())
				.get().uri("/client")
				.exchange()
				.expectStatus().isOk();

		OAuth2AuthorizedClient client = this.controller.authorizedClient;
		assertThat(client).isNotNull();
		assertThat(client.getClientRegistration().getRegistrationId()).isEqualTo("test");
		assertThat(client.getAccessToken().getTokenValue()).isEqualTo("access-token");
		assertThat(client.getRefreshToken()).isNull();
	}

	@Test
	public void oauth2LoginWhenAuthoritiesSpecifiedThenGrantsAccess() {
		this.client.mutateWith(mockOAuth2Login()
				.authorities(new SimpleGrantedAuthority("SCOPE_admin")))
				.get().uri("/token")
				.exchange()
				.expectStatus().isOk();

		OAuth2AuthenticationToken token = this.controller.token;
		assertThat((Collection<GrantedAuthority>) token.getPrincipal().getAuthorities())
				.contains(new SimpleGrantedAuthority("SCOPE_admin"));
	}

	@Test
	public void oauth2LoginWhenAttributeSpecifiedThenUserHasAttribute() {
		this.client.mutateWith(mockOAuth2Login()
				.attributes(a -> a.put("iss", "https://idp.example.org")))
				.get().uri("/token")
				.exchange()
				.expectStatus().isOk();

		OAuth2AuthenticationToken token = this.controller.token;
		assertThat(token.getPrincipal().getAttributes())
				.containsEntry("iss", "https://idp.example.org");
	}

	@Test
	public void oauth2LoginWhenOAuth2UserSpecifiedThenLastCalledTakesPrecedence() throws Exception {
		OAuth2User oauth2User = new DefaultOAuth2User(
				AuthorityUtils.createAuthorityList("SCOPE_user"),
				Collections.singletonMap("sub", "subject"),
				"sub");

		this.client.mutateWith(mockOAuth2Login()
				.attributes(a -> a.put("subject", "foo"))
				.oauth2User(oauth2User))
				.get().uri("/token")
				.exchange()
				.expectStatus().isOk();

		OAuth2AuthenticationToken token = this.controller.token;
		assertThat(token.getPrincipal().getAttributes())
				.containsEntry("sub", "subject");

		this.client.mutateWith(mockOAuth2Login()
				.oauth2User(oauth2User)
				.attributes(a -> a.put("sub", "bar")))
				.get().uri("/token")
				.exchange()
				.expectStatus().isOk();

		token = this.controller.token;
		assertThat(token.getPrincipal().getAttributes())
				.containsEntry("sub", "bar");
	}

	@RestController
	static class OAuth2LoginController {
		volatile OAuth2AuthenticationToken token;
		volatile OAuth2AuthorizedClient authorizedClient;

		@GetMapping("/token")
		OAuth2AuthenticationToken token(OAuth2AuthenticationToken token) {
			this.token = token;
			return token;
		}

		@GetMapping("/client")
		String authorizedClient
				(@RegisteredOAuth2AuthorizedClient OAuth2AuthorizedClient authorizedClient) {
			this.authorizedClient = authorizedClient;
			return authorizedClient.getPrincipalName();
		}
	}
}
