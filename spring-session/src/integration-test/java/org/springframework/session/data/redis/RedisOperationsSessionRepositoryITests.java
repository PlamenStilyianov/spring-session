/*
 * Copyright 2002-2015 the original author or authors.
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
package org.springframework.session.data.redis;

import static org.fest.assertions.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.session.events.SessionDestroyedEvent;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import redis.embedded.RedisServer;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration
public class RedisOperationsSessionRepositoryITests<S extends Session> {
	@Autowired
	private SessionRepository<S> repository;

	@Autowired
	private SessionDestroyedEventRegistry registry;

	private final Object lock = new Object();

	@Before
	public void setup() {
		registry.setLock(lock);
	}

	@Test
	public void saves() throws InterruptedException {
		S toSave = repository.createSession();
		toSave.setAttribute("a", "b");
		Authentication toSaveToken = new UsernamePasswordAuthenticationToken("user","password", AuthorityUtils.createAuthorityList("ROLE_USER"));
		SecurityContext toSaveContext = SecurityContextHolder.createEmptyContext();
		toSaveContext.setAuthentication(toSaveToken);
		toSave.setAttribute("SPRING_SECURITY_CONTEXT", toSaveContext);

		repository.save(toSave);

		Session session = repository.getSession(toSave.getId());

		assertThat(session.getId()).isEqualTo(toSave.getId());
		assertThat(session.getAttributeNames()).isEqualTo(session.getAttributeNames());
		assertThat(session.getAttribute("a")).isEqualTo(toSave.getAttribute("a"));

		repository.delete(toSave.getId());

		assertThat(repository.getSession(toSave.getId())).isNull();
		synchronized (lock) {
			lock.wait(3000);
		}
		assertThat(registry.receivedEvent()).isTrue();
	}

	@Test
	public void putAllOnSingleAttrDoesNotRemoveOld() {
		S toSave = repository.createSession();
		toSave.setAttribute("a", "b");

		repository.save(toSave);
		toSave = repository.getSession(toSave.getId());

		toSave.setAttribute("1", "2");

		repository.save(toSave);
		toSave = repository.getSession(toSave.getId());

		Session session = repository.getSession(toSave.getId());
		assertThat(session.getAttributeNames().size()).isEqualTo(2);
		assertThat(session.getAttribute("a")).isEqualTo("b");
		assertThat(session.getAttribute("1")).isEqualTo("2");
	}

	static class SessionDestroyedEventRegistry implements ApplicationListener<SessionDestroyedEvent> {
		private boolean receivedEvent;
		private Object lock;

		public void onApplicationEvent(SessionDestroyedEvent event) {
			receivedEvent = true;
			synchronized (lock) {
				lock.notifyAll();
			}
		}

		public boolean receivedEvent() {
			return receivedEvent;
		}

		public void setLock(Object lock) {
			this.lock = lock;
		}
	}

	@Configuration
	@EnableRedisHttpSession
	static class Config {
		@Bean
		public JedisConnectionFactory connectionFactory() throws Exception {
			JedisConnectionFactory factory = new JedisConnectionFactory();
			factory.setPort(getPort());
			factory.setUsePool(false);
			return factory;
		}

		@Bean
		public static RedisServerBean redisServer() {
			return new RedisServerBean();
		}

		@Bean
		public SessionDestroyedEventRegistry sessionDestroyedEventRegistry() {
			return new SessionDestroyedEventRegistry();
		}

		/**
		 * Implements BeanDefinitionRegistryPostProcessor to ensure this Bean
		 * is initialized before any other Beans. Specifically, we want to ensure
		 * that the Redis Server is started before RedisHttpSessionConfiguration
		 * attempts to enable Keyspace notifications.
		 */
		static class RedisServerBean implements InitializingBean, DisposableBean, BeanDefinitionRegistryPostProcessor {
			private RedisServer redisServer;


			public void afterPropertiesSet() throws Exception {
				redisServer = new RedisServer(getPort());
				redisServer.start();
			}

			public void destroy() throws Exception {
				if(redisServer != null) {
					redisServer.stop();
				}
			}

			public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {}

			public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {}
		}
	}

	private static Integer availablePort;

	private static int getPort() throws IOException {
		if(availablePort == null) {
			ServerSocket socket = new ServerSocket(0);
			availablePort = socket.getLocalPort();
			socket.close();
		}
		return availablePort;
	}
}