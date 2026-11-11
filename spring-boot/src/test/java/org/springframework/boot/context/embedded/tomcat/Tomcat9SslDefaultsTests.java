/*
 * Copyright 2012-2026 the original author or authors.
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

package org.springframework.boot.context.embedded.tomcat;

import java.lang.reflect.Method;

import javax.servlet.http.HttpServletRequest;

import org.apache.coyote.http11.Http11NioProtocol;
import org.junit.Test;

import org.springframework.boot.context.embedded.Ssl;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the cipher defaults required by Tomcat 9.
 */
public class Tomcat9SslDefaultsTests {

	@Test
	public void servletApiSupportsTomcat9Dispatch() throws Exception {
		Method mapping = HttpServletRequest.class.getMethod("getHttpServletMapping");
		assertThat(mapping.getReturnType().getName()).isEqualTo("javax.servlet.http.HttpServletMapping");
	}

	@Test
	public void unspecifiedCiphersPreserveTomcatDefaults() {
		Http11NioProtocol protocol = new Http11NioProtocol();
		String defaults = protocol.getCiphers();
		new TomcatEmbeddedServletContainerFactory().configureSsl(protocol, ssl(null));
		assertThat(protocol.getCiphers()).isEqualTo(defaults);
	}

	@Test
	public void emptyCiphersPreserveTomcatDefaults() {
		Http11NioProtocol protocol = new Http11NioProtocol();
		String defaults = protocol.getCiphers();
		new TomcatEmbeddedServletContainerFactory().configureSsl(protocol, ssl(new String[0]));
		assertThat(protocol.getCiphers()).isEqualTo(defaults);
	}

	@Test
	public void explicitCiphersAreApplied() {
		Http11NioProtocol protocol = new Http11NioProtocol();
		new TomcatEmbeddedServletContainerFactory().configureSsl(protocol,
				ssl(new String[] { "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256" }));
		assertThat(protocol.getCiphers()).isEqualTo("ECDHE-RSA-AES128-GCM-SHA256");
		assertThat(protocol.findSslHostConfigs()[0].getJsseCipherNames())
				.contains("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
	}

	private Ssl ssl(String[] ciphers) {
		Ssl ssl = new Ssl();
		ssl.setKeyStore("test.jks");
		ssl.setKeyStorePassword("secret");
		ssl.setCiphers(ciphers);
		return ssl;
	}

}
