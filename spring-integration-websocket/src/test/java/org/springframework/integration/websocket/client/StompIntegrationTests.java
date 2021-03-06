/*
 * Copyright 2014-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.websocket.client;

import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;
import org.junit.runner.RunWith;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.integration.annotation.Gateway;
import org.springframework.integration.annotation.IntegrationComponentScan;
import org.springframework.integration.annotation.MessagingGateway;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.annotation.Transformer;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.channel.QueueChannel;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.integration.core.MessageProducer;
import org.springframework.integration.event.inbound.ApplicationEventListeningMessageProducer;
import org.springframework.integration.test.util.TestUtils;
import org.springframework.integration.transformer.ExpressionEvaluatingTransformer;
import org.springframework.integration.websocket.ClientWebSocketContainer;
import org.springframework.integration.websocket.IntegrationWebSocketContainer;
import org.springframework.integration.websocket.TomcatWebSocketTestServer;
import org.springframework.integration.websocket.event.ReceiptEvent;
import org.springframework.integration.websocket.inbound.WebSocketInboundChannelAdapter;
import org.springframework.integration.websocket.outbound.WebSocketOutboundMessageHandler;
import org.springframework.integration.websocket.support.SubProtocolHandlerRegistry;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.PollableChannel;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.broker.SubscriptionRegistry;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.AbstractSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Controller;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.config.annotation.AbstractWebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.messaging.AbstractSubProtocolEvent;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.StompSubProtocolHandler;
import org.springframework.web.socket.messaging.SubProtocolHandler;
import org.springframework.web.socket.server.standard.TomcatRequestUpgradeStrategy;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

/**
 * @author Artem Bilan
 * @since 4.1
 */
@ContextConfiguration
@RunWith(SpringJUnit4ClassRunner.class)
@DirtiesContext
public class StompIntegrationTests {

	private static final SpelExpressionParser PARSER = new SpelExpressionParser();

	@Value("#{server.serverContext}")
	private ApplicationContext serverContext;

	@Autowired
	@Qualifier("webSocketOutputChannel")
	private MessageChannel webSocketOutputChannel;

	@Autowired
	@Qualifier("webSocketInputChannel")
	private QueueChannel webSocketInputChannel;

	@Autowired
	@Qualifier("webSocketEvents")
	private PollableChannel webSocketEvents;

	@Test
	public void sendMessageToController() throws Exception {
		StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.CONNECT);
		this.webSocketOutputChannel.send(MessageBuilder.withPayload(new byte[0]).setHeaders(headers).build());

		Message<?> receive = this.webSocketEvents.receive(10000);
		assertNotNull(receive);
		Object event = receive.getPayload();
		assertThat(event, instanceOf(SessionConnectedEvent.class));
		Message<?> connectedMessage = ((SessionConnectedEvent) event).getMessage();
		headers = StompHeaderAccessor.wrap(connectedMessage);
		assertEquals(StompCommand.CONNECTED, headers.getCommand());

		headers = StompHeaderAccessor.create(StompCommand.SEND);
		headers.setSubscriptionId("sub1");
		headers.setDestination("/app/simple");
		Message<String> message = MessageBuilder.withPayload("foo").setHeaders(headers).build();

		this.webSocketOutputChannel.send(message);

		SimpleController controller = this.serverContext.getBean(SimpleController.class);
		assertTrue(controller.latch.await(10, TimeUnit.SECONDS));
	}

	@Test
	public void sendMessageToControllerAndReceiveReplyViaTopic() throws Exception {

		StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
		headers.setSubscriptionId("subs1");
		headers.setDestination("/topic/increment");
		headers.setReceipt("myReceipt");
		Message<byte[]> message = MessageBuilder.withPayload(ByteBuffer.allocate(0).array())
				.setHeaders(headers)
				.build();

		this.webSocketOutputChannel.send(message);

		Message<?> receive = this.webSocketEvents.receive(10000);
		assertNotNull(receive);
		Object event = receive.getPayload();
		assertThat(event, instanceOf(ReceiptEvent.class));
		Message<?> receiptMessage = ((ReceiptEvent) event).getMessage();
		headers = StompHeaderAccessor.wrap(receiptMessage);
		assertEquals(StompCommand.RECEIPT, headers.getCommand());
		assertEquals("myReceipt", headers.getReceiptId());

		waitForSubscribe("increment");

		headers = StompHeaderAccessor.create(StompCommand.SEND);
		headers.setSubscriptionId("subs1");
		headers.setDestination("/app/increment");
		Message<Integer> message2 = MessageBuilder.withPayload(5).setHeaders(headers).build();

		this.webSocketOutputChannel.send(message2);

		receive = webSocketInputChannel.receive(10000);
		assertNotNull(receive);
		assertEquals("6", receive.getPayload());
	}

	@Test
	public void sendMessageToBrokerAndReceiveReplyViaTopic() throws Exception {

		StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
		headers.setSubscriptionId("subs1");
		headers.setDestination("/topic/foo");
		Message<byte[]> message = MessageBuilder.withPayload(ByteBuffer.allocate(0).array())
				.setHeaders(headers)
				.build();

		headers = StompHeaderAccessor.create(StompCommand.SEND);
		headers.setSubscriptionId("subs1");
		headers.setDestination("/topic/foo");
		Message<Integer> message2 = MessageBuilder.withPayload(10).setHeaders(headers).build();

		this.webSocketOutputChannel.send(message);

		waitForSubscribe("foo");

		this.webSocketOutputChannel.send(message2);

		Message<?> receive = webSocketInputChannel.receive(10000);
		assertNotNull(receive);
		assertEquals("10", receive.getPayload());
	}

	@Test
	public void sendSubscribeToControllerAndReceiveReply() throws Exception {

		String destHeader = "/app/number";

		StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
		headers.setSubscriptionId("subs1");
		headers.setDestination(destHeader);
		Message<byte[]> message = MessageBuilder.withPayload(ByteBuffer.allocate(0).array())
				.setHeaders(headers)
				.build();

		this.webSocketOutputChannel.send(message);

		Message<?> receive = webSocketInputChannel.receive(10000);
		assertNotNull(receive);

		StompHeaderAccessor stompHeaderAccessor = StompHeaderAccessor.wrap(receive);

		assertEquals("Expected STOMP destination=/app/number, got " + stompHeaderAccessor,
				destHeader, stompHeaderAccessor.getDestination());

		Object payload = receive.getPayload();

		assertEquals("Expected STOMP Payload=42, got " + payload, "42", payload);
	}

	@Test
	public void handleExceptionAndSendToUser() throws Exception {
		String destHeader = "/user/queue/error";

		StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
		headers.setSubscriptionId("subs1");
		headers.setDestination(destHeader);
		Message<byte[]> message = MessageBuilder.withPayload(ByteBuffer.allocate(0).array())
				.setHeaders(headers)
				.build();

		headers = StompHeaderAccessor.create(StompCommand.SEND);
		headers.setSubscriptionId("subs1");
		headers.setDestination("/app/exception");
		Message<String> message2 = MessageBuilder.withPayload("foo").setHeaders(headers).build();

		this.webSocketOutputChannel.send(message);

		waitForSubscribe("error");

		this.webSocketOutputChannel.send(message2);


		Message<?> receive = webSocketInputChannel.receive(10000);
		assertNotNull(receive);

		StompHeaderAccessor stompHeaderAccessor = StompHeaderAccessor.wrap(receive);

		assertEquals("Expected STOMP destination=/user/queue/error, got " + stompHeaderAccessor,
				destHeader, stompHeaderAccessor.getDestination());

		assertEquals("Got error: Bad input", receive.getPayload());
	}

	@Test
	public void sendMessageToGateway() throws Exception {

		StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
		headers.setSubscriptionId("subs1");
		headers.setDestination("/user/queue/answer");
		Message<byte[]> message = MessageBuilder.withPayload(ByteBuffer.allocate(0).array())
				.setHeaders(headers)
				.build();

		headers = StompHeaderAccessor.create(StompCommand.SEND);
		headers.setSubscriptionId("subs1");
		headers.setDestination("/app/greeting");
		Message<String> message2 = MessageBuilder.withPayload("Bob").setHeaders(headers).build();

		this.webSocketOutputChannel.send(message);

		waitForSubscribe("answer");

		this.webSocketOutputChannel.send(message2);

		Message<?> receive = webSocketInputChannel.receive(10000);
		assertNotNull(receive);
		assertEquals("Hello Bob", receive.getPayload());
	}

	private void waitForSubscribe(String destination) throws InterruptedException {
		SimpleBrokerMessageHandler serverBrokerMessageHandler =
				this.serverContext.getBean("simpleBrokerMessageHandler", SimpleBrokerMessageHandler.class);

		SubscriptionRegistry subscriptionRegistry = serverBrokerMessageHandler.getSubscriptionRegistry();

		int n = 0;
		while (!containsDestination(destination, subscriptionRegistry) && n++ < 100) {
			Thread.sleep(100);
		}

		assertTrue("The subscription for the '" + destination + "' destination hasn't been registered", n < 100);
	}

	@SuppressWarnings("rawtypes")
	private boolean containsDestination(String destination, SubscriptionRegistry subscriptionRegistry) {
		Map sessions = TestUtils.getPropertyValue(subscriptionRegistry, "subscriptionRegistry.sessions", Map.class);
		for (Object info : sessions.values()) {
			Map subscriptions = TestUtils.getPropertyValue(info, "subscriptions", Map.class);
			for (Object dest : subscriptions.keySet()) {
				if (((String) dest).contains(destination)) {
					return true;
				}
			}
		}
		return false;
	}

	@Configuration
	@EnableIntegration
	public static class ContextConfiguration {

		@Bean
		public TomcatWebSocketTestServer server() {
			return new TomcatWebSocketTestServer(ServerConfig.class);
		}

		@Bean
		public IntegrationWebSocketContainer clientWebSocketContainer() {
			return new ClientWebSocketContainer(new StandardWebSocketClient(), server().getWsBaseUrl() + "/ws/websocket");
		}

		@Bean
		public SubProtocolHandler stompSubProtocolHandler() {
			return new StompSubProtocolHandler();
		}

		@Bean
		public MessageChannel webSocketInputChannel() {
			return new QueueChannel();
		}

		@Bean
		public MessageChannel webSocketOutputChannel() {
			return new DirectChannel();
		}

		@Bean
		public MessageProducer webSocketInboundChannelAdapter() {
			WebSocketInboundChannelAdapter webSocketInboundChannelAdapter =
					new WebSocketInboundChannelAdapter(clientWebSocketContainer(),
							new SubProtocolHandlerRegistry(stompSubProtocolHandler()));
			webSocketInboundChannelAdapter.setOutputChannel(webSocketInputChannel());
			return webSocketInboundChannelAdapter;
		}

		@Bean
		@ServiceActivator(inputChannel = "webSocketOutputChannel")
		public MessageHandler webSocketOutboundMessageHandler() {
			return new WebSocketOutboundMessageHandler(clientWebSocketContainer(),
					new SubProtocolHandlerRegistry(stompSubProtocolHandler()));
		}

		@Bean
		public PollableChannel webSocketEvents() {
			return new QueueChannel();
		}

		@Bean
		@SuppressWarnings("unchecked")
		public ApplicationListener<ApplicationEvent> webSocketEventListener() {
			ApplicationEventListeningMessageProducer producer = new ApplicationEventListeningMessageProducer();
			producer.setEventTypes(AbstractSubProtocolEvent.class);
			producer.setOutputChannel(webSocketEvents());
			return producer;
		}
	}

	// WebSocket Server part

	@Target({ElementType.TYPE})
	@Retention(RetentionPolicy.RUNTIME)
	@Controller
	private @interface IntegrationTestController {
	}

	@IntegrationTestController
	static class SimpleController {

		private final CountDownLatch latch = new CountDownLatch(1);

		@MessageMapping(value = "/simple")
		public void handle() {
			this.latch.countDown();
		}

		@MessageMapping(value = "/exception")
		public void handleWithError() {
			throw new IllegalArgumentException("Bad input");
		}

		@MessageExceptionHandler
		@SendToUser("/queue/error")
		public String handleException(IllegalArgumentException ex) {
			return "Got error: " + ex.getMessage();
		}
	}

	@IntegrationTestController
	static class IncrementController {

		@MessageMapping(value = "/increment")
		public int handle(int i) {
			return i + 1;
		}

		@SubscribeMapping("/number")
		public int number() {
			return 42;
		}
	}


	@MessagingGateway
	@Controller
	static interface WebSocketGateway {

		@MessageMapping("/greeting")
		@SendToUser("/queue/answer")
		@Gateway(requestChannel = "greetingChannel")
		String greeting(String payload);

	}

	@Configuration
	@EnableWebSocketMessageBroker
	@EnableIntegration
	@ComponentScan(
			basePackageClasses = StompIntegrationTests.class,
			useDefaultFilters = false,
			includeFilters = @ComponentScan.Filter(IntegrationTestController.class))
	@IntegrationComponentScan
	static class ServerConfig extends AbstractWebSocketMessageBrokerConfigurer {

		private static final ExpressionParser expressionParser = new SpelExpressionParser();

		@Bean
		public MessageChannel greetingChannel() {
			return new DirectChannel();
		}

		@Bean
		@Transformer(inputChannel = "greetingChannel")
		public ExpressionEvaluatingTransformer greetingTransformer() {
			return new ExpressionEvaluatingTransformer(expressionParser.parseExpression("'Hello ' + payload"));
		}

		@Bean
		public DefaultHandshakeHandler handshakeHandler() {
			return new DefaultHandshakeHandler(new TomcatRequestUpgradeStrategy());
		}

		@Override
		public void registerStompEndpoints(StompEndpointRegistry registry) {
			registry.addEndpoint("/ws").setHandshakeHandler(handshakeHandler()).withSockJS();
		}

		@Override
		public void configureMessageBroker(MessageBrokerRegistry configurer) {
			configurer.setApplicationDestinationPrefixes("/app");
			configurer.enableSimpleBroker("/topic", "/queue");
		}

		//TODO SimpleBrokerMessageHandler doesn't support RECEIPT frame, hence we emulate it this way
		@Bean
		@SuppressWarnings("unchecked")
		public ApplicationListener<SessionSubscribeEvent> webSocketEventListener(
				final AbstractSubscribableChannel clientOutboundChannel) {
			return new ApplicationListener<SessionSubscribeEvent>() {

				@Override
				public void onApplicationEvent(SessionSubscribeEvent event) {
					Message<byte[]> message = event.getMessage();
					StompHeaderAccessor stompHeaderAccessor = StompHeaderAccessor.wrap(message);
					if (stompHeaderAccessor.getReceipt() != null) {
						stompHeaderAccessor.setHeader("stompCommand", StompCommand.RECEIPT);
						stompHeaderAccessor.setReceiptId(stompHeaderAccessor.getReceipt());
						clientOutboundChannel.send(
								MessageBuilder.createMessage(new byte[0], stompHeaderAccessor.getMessageHeaders()));
					}
				}

			};
		}

	}

}
