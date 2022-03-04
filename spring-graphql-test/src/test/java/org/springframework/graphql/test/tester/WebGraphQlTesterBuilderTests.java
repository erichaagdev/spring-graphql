/*
 * Copyright 2002-2022 the original author or authors.
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

package org.springframework.graphql.test.tester;

import java.net.URI;
import java.time.Duration;
import java.util.stream.Stream;

import graphql.ExecutionInput;
import graphql.ExecutionResultImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;

import org.springframework.graphql.RequestOutput;
import org.springframework.graphql.web.TestWebSocketClient;
import org.springframework.graphql.web.TestWebSocketConnection;
import org.springframework.graphql.support.DocumentSource;
import org.springframework.graphql.web.WebGraphQlHandler;
import org.springframework.graphql.web.WebInput;
import org.springframework.graphql.web.WebInterceptor;
import org.springframework.graphql.web.WebOutput;
import org.springframework.graphql.web.webflux.GraphQlHttpHandler;
import org.springframework.graphql.web.webflux.GraphQlWebSocketHandler;
import org.springframework.http.codec.ClientCodecConfigurer;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.socket.WebSocketHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;


/**
 * Tests for the builders of Web {@code GraphQlTester} extensions, using a
 * {@link WebInterceptor} to capture the WebInput on the server side, and return
 * with no handling.
 *
 * <ul>
 * <li>{@link HttpGraphQlTester} via {@link WebTestClient} to {@link GraphQlHttpHandler}
 * <li>{@link WebSocketGraphQlTester} via a {@link TestWebSocketConnection} to {@link GraphQlWebSocketHandler}
 * <li>{@link WebGraphQlTester} via direct call to {@link WebGraphQlHandler}
 * </ul>
 *
 * @author Rossen Stoyanchev
 */
public class WebGraphQlTesterBuilderTests {

	private static final String DOCUMENT = "{ Query }";


	public static Stream<TesterBuilderSetup> argumentSource() {
		return Stream.of(new WebBuilderSetup(), new HttpBuilderSetup(), new WebSocketBuilderSetup());
	}


	@ParameterizedTest
	@MethodSource("argumentSource")
	void mutateUrlHeaders(TesterBuilderSetup builderSetup) {

		String url = "/graphql-one";

		// Original
		WebGraphQlTester.Builder<?> builder = builderSetup.initBuilder()
				.url(url)
				.headers(headers -> headers.add("h", "one"));

		WebGraphQlTester tester = builder.build();
		tester.document(DOCUMENT).execute();

		WebInput input = builderSetup.getWebInput();
		assertThat(input.getUri().toString()).isEqualTo(url);
		assertThat(input.getHeaders().get("h")).containsExactly("one");

		// Mutate to add header value
		builder = tester.mutate().headers(headers -> headers.add("h", "two"));
		tester = builder.build();
		tester.document(DOCUMENT).execute();
		assertThat(builderSetup.getWebInput().getHeaders().get("h")).containsExactly("one", "two");

		// Mutate to replace header
		builder = tester.mutate().header("h", "three", "four");
		tester = builder.build();
		tester.document(DOCUMENT).execute();

		input = builderSetup.getWebInput();
		assertThat(input.getUri().toString()).isEqualTo(url);
		assertThat(input.getHeaders().get("h")).containsExactly("three", "four");
	}

	@Test
	void mutateWebTestClientViaConsumer() {
		HttpBuilderSetup testerSetup = new HttpBuilderSetup();

		// Original header value
		HttpGraphQlTester.Builder<?> builder = testerSetup.initBuilder()
				.webTestClient(testClientBuilder -> testClientBuilder.defaultHeaders(h -> h.add("h", "one")));

		HttpGraphQlTester tester = builder.build();
		tester.document(DOCUMENT).execute();
		assertThat(testerSetup.getWebInput().getHeaders().get("h")).containsExactly("one");

		// Mutate to add header value
		HttpGraphQlTester.Builder<?> builder2 = tester.mutate()
				.webTestClient(testClientBuilder -> testClientBuilder.defaultHeaders(h -> h.add("h", "two")));

		tester = builder2.build();
		tester.document(DOCUMENT).execute();
		assertThat(testerSetup.getWebInput().getHeaders().get("h")).containsExactly("one", "two");

		// Mutate to replace header
		HttpGraphQlTester.Builder<?> builder3 = tester.mutate()
				.webTestClient(testClientBuilder -> testClientBuilder.defaultHeader("h", "three"));

		tester = builder3.build();
		tester.document(DOCUMENT).execute();
		assertThat(testerSetup.getWebInput().getHeaders().get("h")).containsExactly("three");
	}

	@ParameterizedTest
	@MethodSource("argumentSource")
	void mutateDocumentSource(TesterBuilderSetup builderSetup) {

		DocumentSource documentSource = name -> name.equals("name") ?
				Mono.just(DOCUMENT) : Mono.error(new IllegalArgumentException());

		// Original
		WebGraphQlTester.Builder<?> builder = builderSetup.initBuilder().documentSource(documentSource);
		WebGraphQlTester tester = builder.build();
		tester.documentName("name").execute();

		WebInput input = builderSetup.getWebInput();
		assertThat(input.getDocument()).isEqualTo(DOCUMENT);

		// Mutate
		tester = tester.mutate().build();
		tester.documentName("name").execute();

		input = builderSetup.getWebInput();
		assertThat(input.getDocument()).isEqualTo(DOCUMENT);
	}


	private interface TesterBuilderSetup {

		WebGraphQlTester.Builder<?> initBuilder();

		WebInput getWebInput();

	}


	private static class WebBuilderSetup implements TesterBuilderSetup {

		private WebInput webInput;

		@Override
		public WebGraphQlTester.Builder<?> initBuilder() {
			return WebGraphQlTester.builder(webGraphQlHandler());
		}

		protected WebGraphQlHandler webGraphQlHandler() {
			return WebGraphQlHandler.builder(requestInput -> Mono.error(new UnsupportedOperationException()))
					.interceptor((input, chain) -> {
						this.webInput = input;
						return Mono.just(new WebOutput(new RequestOutput(
								ExecutionInput.newExecutionInput().query("{ notUsed }").build(),
								ExecutionResultImpl.newExecutionResult().build())));
					})
					.build();
		}

		@Override
		public WebInput getWebInput() {
			return this.webInput;
		}

	}


	private static class HttpBuilderSetup extends WebBuilderSetup {

		@Override
		public HttpGraphQlTester.Builder<?> initBuilder() {
			GraphQlHttpHandler handler = new GraphQlHttpHandler(webGraphQlHandler());
			RouterFunction<ServerResponse> routerFunction = route().POST("/**", handler::handleRequest).build();
			return HttpGraphQlTester.builder(WebTestClient.bindToRouterFunction(routerFunction).configureClient());
		}

	}


	private static class WebSocketBuilderSetup extends WebBuilderSetup {

		@Override
		public WebSocketGraphQlTester.Builder<?> initBuilder() {
			ClientCodecConfigurer configurer = ClientCodecConfigurer.create();
			WebSocketHandler handler = new GraphQlWebSocketHandler(webGraphQlHandler(), configurer, Duration.ofSeconds(5));
			return WebSocketGraphQlTester.builder(URI.create(""), new TestWebSocketClient(handler));
		}

	}

}
