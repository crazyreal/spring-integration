/*
 * Copyright 2014 the original author or authors.
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

package org.springframework.integration.redis.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.integration.endpoint.PollingConsumer;
import org.springframework.integration.redis.outbound.RedisQueueOutboundGateway;
import org.springframework.integration.test.util.TestUtils;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.PollableChannel;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

/**
 * @author David Liu
 * since 4.1
 */
@ContextConfiguration
@RunWith(SpringJUnit4ClassRunner.class)
public class RedisQueueOutboundGatewayParserTests {

	@Autowired
	private RedisConnectionFactory connectionFactory;

	@Autowired
	@Qualifier("outboundGateway")
	private PollingConsumer consumer;

	@Autowired
	@Qualifier("outboundGateway.handler")
	private RedisQueueOutboundGateway defaultGateway;

	@Autowired
	@Qualifier("receiveChannel")
	private MessageChannel receiveChannel;

	@Autowired
	@Qualifier("requestChannel")
	private MessageChannel requestChannel;

	@Autowired
	private RedisSerializer<?> serializer;

	@Autowired
	private ApplicationContext context;

	@Test
	public void testDefaultConfig() throws Exception {
		assertFalse(TestUtils.getPropertyValue(this.defaultGateway, "extractPayload", Boolean.class));
		assertSame(this.serializer, TestUtils.getPropertyValue(this.defaultGateway, "serializer"));
		assertTrue(TestUtils.getPropertyValue(this.defaultGateway, "serializerExplicitlySet", Boolean.class));
		assertEquals(2, TestUtils.getPropertyValue(this.defaultGateway, "order"));
		assertSame(this.receiveChannel, TestUtils.getPropertyValue(this.defaultGateway, "outputChannel"));
		assertSame(this.requestChannel, TestUtils.getPropertyValue(this.consumer, "inputChannel"));
		assertFalse(TestUtils.getPropertyValue(this.defaultGateway, "requiresReply", Boolean.class));
		assertEquals(2000, TestUtils.getPropertyValue(this.defaultGateway, "receiveTimeout"));
		assertFalse(TestUtils.getPropertyValue(this.consumer, "autoStartup", Boolean.class));
		assertEquals(3, TestUtils.getPropertyValue(this.consumer, "phase"));
	}

}
