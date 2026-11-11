/*
 * Copyright 2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Permission;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.catalina.Context;
import org.apache.catalina.Valve;
import org.apache.catalina.valves.AccessLogValve;
import org.apache.coyote.AbstractProtocol;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.web.EmbeddedServletContainerAutoConfiguration;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.autoconfigure.web.ServerPropertiesAutoConfiguration;
import org.springframework.boot.context.embedded.ConfigurableEmbeddedServletContainer;
import org.springframework.boot.context.embedded.EmbeddedServletContainerCustomizer;
import org.springframework.boot.context.embedded.EmbeddedWebApplicationContext;
import org.springframework.boot.context.embedded.tomcat.TomcatEmbeddedServletContainer;
import org.springframework.boot.context.embedded.tomcat.TomcatEmbeddedServletContainerFactory;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Explicit Boot web auto-configuration slice. It never imports TPS/MOS application configuration. */
public class BootLifecycleProbe {
    private static final String BODY = "q=è\n" + "Boot15-Tomcat9-fixture\n".repeat(400);
    private static final List<String> EVENTS = new CopyOnWriteArrayList<>();
    private static final List<String> CHECKS = new ArrayList<>();
    private static final Map<String, Object> DETAILS = new LinkedHashMap<>();
    private static String currentCase;
    private static boolean custom;
    private static boolean tls;
    private static boolean compressed;
    private static final NetworkGuard NETWORK = new NetworkGuard();

    public static class NetworkGuard extends SecurityManager {
        final java.util.Set<Integer> ports = ConcurrentHashMap.newKeySet();
        int denied;
        @Override public void checkPermission(Permission permission) { }
        @Override public void checkPermission(Permission permission, Object context) { }
        @Override public void checkConnect(String host, int port) {
            boolean local = host.equals("127.0.0.1") || host.equals("localhost") || host.equals("::1");
            if ((port == -1 && (local || host.isEmpty())) || (local && ports.contains(port))) return;
            denied++;
            throw new SecurityException("[FIXTURE_NETWORK] Non-fixture connection or DNS lookup denied; port=" + port);
        }
        @Override public void checkConnect(String host, int port, Object context) { checkConnect(host, port); }
        @Override public void checkMulticast(InetAddress address) { throw new SecurityException("[FIXTURE_NETWORK] Multicast denied"); }
    }

    @Configuration
    @ImportAutoConfiguration({PropertyPlaceholderAutoConfiguration.class, ServerPropertiesAutoConfiguration.class, EmbeddedServletContainerAutoConfiguration.class})
    public static class WebSlice {
        @Bean public ServletRegistrationBean fixtureServlet() {
            return new ServletRegistrationBean(new HttpServlet() {
                @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws java.io.IOException {
                    if (request.getServletPath().equals("/fail")) {
                        throw new IllegalStateException("fixture planned failure");
                    } else if (request.getServletPath().equals("/mapped-error")) {
                        response.setContentType("text/plain");
                        response.getWriter().write("mapped-error");
                    } else if (request.getServletPath().equals("/session")) {
                        javax.servlet.http.HttpSession session = request.getSession();
                        Integer counter = (Integer) session.getAttribute("counter");
                        counter = counter == null ? 1 : counter + 1;
                        session.setAttribute("counter", counter);
                        response.setContentType("text/plain;charset=UTF-8");
                        response.getWriter().write("count=" + counter + ";timeout=" + session.getMaxInactiveInterval());
                    } else {
                        response.setContentType(request.getServletPath().equals("/binary") ? "application/octet-stream" : "text/plain;charset=UTF-8");
                        response.getWriter().write(request.getServletPath().equals("/small") ? "small" : "q=" + request.getParameter("q") + "\n" + "Boot15-Tomcat9-fixture\n".repeat(400));
                    }
                    DETAILS.put("contextParameter", request.getServletContext().getInitParameter("fixture-param"));
                }
                @Override protected void doPut(HttpServletRequest request, HttpServletResponse response) throws java.io.IOException {
                    doGet(request, response);
                }
            }, "/probe", "/session", "/small", "/binary", "/fail", "/mapped-error");
        }
        @Bean public org.springframework.boot.web.servlet.ErrorPageRegistrar errorPages() {
            return registry -> registry.addErrorPages(new org.springframework.boot.web.servlet.ErrorPage(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "/mapped-error"));
        }
        @Bean public EmbeddedServletContainerCustomizer beforeProperties() { return new Before(); }
        @Bean public EmbeddedServletContainerCustomizer afterProperties() { return new After(); }
    }

    public static class Before implements EmbeddedServletContainerCustomizer, Ordered {
        @Override public int getOrder() { return -100; }
        @Override public void customize(ConfigurableEmbeddedServletContainer container) {
            EVENTS.add("before-properties");
            DETAILS.put("portBeforeServerProperties", ((TomcatEmbeddedServletContainerFactory) container).getPort());
            ((TomcatEmbeddedServletContainerFactory) container).addConnectorCustomizers(connector -> {
                EVENTS.add("connector-before-standard");
                ((AbstractProtocol<?>) connector.getProtocolHandler()).setConnectionLinger(2);
            });
        }
    }
    public static class After implements EmbeddedServletContainerCustomizer, Ordered {
        @Override public int getOrder() { return 100; }
        @Override public void customize(ConfigurableEmbeddedServletContainer container) {
            EVENTS.add("after-properties");
            TomcatEmbeddedServletContainerFactory factory = (TomcatEmbeddedServletContainerFactory) container;
            DETAILS.put("portAfterServerProperties", factory.getPort());
            DETAILS.put("contextAfterServerProperties", factory.getContextPath());
            DETAILS.put("compressionStillOnFactory", factory.getCompression() != null && factory.getCompression().getEnabled());
            factory.addConnectorCustomizers(connector -> {
                EVENTS.add("connector-after-standard");
                AbstractProtocol<?> protocol = (AbstractProtocol<?>) connector.getProtocolHandler();
                DETAILS.put("acceptCountAtLastCustomizer", protocol.getAcceptCount());
                DETAILS.put("lastCustomizerRunsBeforeBind", protocol.getLocalPort() < 0);
                DETAILS.put("firstCustomizerPreserved", protocol.getConnectionLinger() == 2);
                protocol.setConnectionLinger(11);
            });
        }
    }

    public static class ApplicationServerProperties extends ServerProperties implements org.springframework.context.ApplicationContextAware {
        private org.springframework.context.ApplicationContext applicationContext;
        @Override public void setApplicationContext(org.springframework.context.ApplicationContext context) { applicationContext = context; }
        @Override public void customize(ConfigurableEmbeddedServletContainer container) {
            DETAILS.put("applicationPropertiesBoundAcceptCount", getTomcat().getAcceptCount());
            DETAILS.put("applicationPropertiesBoundContextPath", getContextPath());
            DETAILS.put("serverPropertiesBeanNamesAtReplacement", Arrays.asList(applicationContext.getBeanNamesForType(ServerProperties.class)));
            EVENTS.add("application-properties-replacement-called");
            super.customize(container);
        }
    }
    @Configuration
    public static class ReplacementRegistration {
        @Bean public ServerProperties applicationServerProperties() { return new ApplicationServerProperties(); }
    }
    @Configuration
    public static class DuplicateRegistration {
        @Bean public ServerProperties firstServerProperties() { return new ServerProperties(); }
        @Bean @org.springframework.context.annotation.Primary public ServerProperties secondServerProperties() { return new ServerProperties(); }
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(currentCase + ": " + label);
        CHECKS.add(label);
        System.out.println("CHECK PASS " + currentCase + " " + label);
    }
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        Path run = Paths.get(args[1]);
        Files.createDirectories(run);
        System.setSecurityManager(NETWORK);
        List<Map<String, Object>> results = new ArrayList<>();
        List<String> cases = mode.equals("normal") ? Arrays.asList("http-defaults", "http-custom", "tls-default-ciphers", "tls-custom") : Arrays.asList(mode);
        boolean allPassed = true;
        for (String name : cases) {
            currentCase = name; custom = !name.equals("http-defaults") && !name.equals("tls-default-ciphers"); tls = name.startsWith("tls"); compressed = mode.equals("normal") && !name.equals("http-defaults");
            EVENTS.clear(); CHECKS.clear(); DETAILS.clear();
            Path base = run.resolve(name);
            Files.createDirectories(base);
            List<String> properties = new ArrayList<>(Arrays.asList("--spring.config.name=boot15-tomcat9-isolated-fixture", "--spring.profiles.active=boot15-probe", "--spring.main.banner-mode=off", "--server.address=127.0.0.1", "--server.port=0", "--server.tomcat.basedir=" + base, "--server.context-parameters.fixture-param=standard-binding", "--logging.file=" + base.resolve("boot.log"), "--debug=true"));
            if (custom) properties.addAll(Arrays.asList("--server.context-path=/compat", "--server.server-header=Boot15Tomcat9Fixture", "--server.max-http-header-size=65536", "--server.connection-timeout=4321", "--server.tomcat.accept-count=137", "--server.tomcat.max-threads=37", "--server.tomcat.min-spare-threads=3", "--server.tomcat.max-connections=123", "--server.tomcat.uri-encoding=UTF-8", "--server.tomcat.accesslog.enabled=true", "--server.tomcat.accesslog.directory=" + base.resolve("audit"), "--server.tomcat.accesslog.prefix=fixture", "--server.tomcat.accesslog.suffix=.log", "--server.tomcat.accesslog.rotate=false", "--server.tomcat.accesslog.buffered=false", "--server.tomcat.accesslog.pattern=%a %m %U %s", "--server.session.timeout=73", "--server.session.tracking-modes=cookie", "--server.session.cookie.name=BOOT15SID", "--server.session.cookie.path=/compat", "--server.session.cookie.http-only=true", "--server.session.cookie.secure=" + tls, "--server.session.cookie.max-age=120"));
            if (compressed) properties.addAll(Arrays.asList("--server.compression.enabled=true", "--server.compression.min-response-size=128", "--server.compression.mime-types=text/plain", "--server.compression.excluded-user-agents=ExcludedFixture/.*"));
            if (tls) properties.addAll(Arrays.asList("--server.ssl.enabled=true", "--server.ssl.key-store=" + run.getParent().resolve("fixture-only.p12").toUri(), "--server.ssl.key-store-type=PKCS12", "--server.ssl.key-store-password=fixture-only", "--server.ssl.enabled-protocols=TLSv1.2"));
            if (name.equals("tls-custom")) properties.add("--server.ssl.ciphers=TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384");
            if (name.equals("tls-custom")) properties.add("--server.ssl.key-alias=fixture");
            List<Class<?>> sources = new ArrayList<>(); sources.add(WebSlice.class);
            if (mode.equals("registration")) sources.add(ReplacementRegistration.class);
            if (mode.equals("duplicate")) sources.add(DuplicateRegistration.class);
            ConfigurableApplicationContext context = null;
            String verdict = "failed";
            String failureText = null;
            try {
                SpringApplication app = new SpringApplication(sources.toArray(new Object[0]));
                app.setWebEnvironment(true); app.setRegisterShutdownHook(false);
                context = app.run(properties.toArray(new String[0]));
                EmbeddedWebApplicationContext web = (EmbeddedWebApplicationContext) context;
                TomcatEmbeddedServletContainer container = (TomcatEmbeddedServletContainer) web.getEmbeddedServletContainer();
                int port = container.getPort(); NETWORK.ports.add(port);
                Map<String, ServerProperties> beans = context.getBeansOfType(ServerProperties.class);
                check(beans.size() == 1, "exactly one ServerProperties bean");
                ServerProperties bound = beans.values().iterator().next();
                TomcatEmbeddedServletContainerFactory factory = context.getBean(TomcatEmbeddedServletContainerFactory.class);
                AbstractHttp11Protocol<?> protocol = (AbstractHttp11Protocol<?>) container.getTomcat().getConnector().getProtocolHandler();
                DETAILS.put("serverPropertiesBeanNames", new ArrayList<>(beans.keySet()));
                DETAILS.put("baseServerPropertiesSource", ServerProperties.class.getProtectionDomain().getCodeSource().getLocation().toString());
                DETAILS.put("factorySource", factory.getClass().getProtectionDomain().getCodeSource().getLocation().toString());
                DETAILS.put("protocolSource", protocol.getClass().getProtectionDomain().getCodeSource().getLocation().toString());
                DETAILS.put("acceptCountBound", bound.getTomcat().getAcceptCount());
                DETAILS.put("actualAcceptCount", protocol.getAcceptCount());
                check(bound.getPort() == 0 && factory.getPort() == 0 && port > 0 && protocol.getAddress().isLoopbackAddress(), "port address and actual listener");
                check(bound.getTomcat().getAcceptCount() == (custom ? 137 : 100) && protocol.getAcceptCount() == bound.getTomcat().getAcceptCount(), "standard accept-count default or customization preserved");
                check(Boolean.TRUE.equals(DETAILS.get("firstCustomizerPreserved")) && protocol.getConnectionLinger() == 11, "both application connector customizers preserved in order");
                check(Boolean.TRUE.equals(DETAILS.get("lastCustomizerRunsBeforeBind")), "standard accept-count applied before bind and before last customizer");
                check(EVENTS.indexOf("before-properties") < EVENTS.indexOf("after-properties") && Integer.valueOf(0).equals(DETAILS.get("portAfterServerProperties")), "Boot container customizer ordering and binding");
                check(bound.getCompression().getEnabled() == compressed && Boolean.valueOf(compressed).equals(DETAILS.get("compressionStillOnFactory")), "compression configuration bean and factory not cleared");
                if (custom) {
                    check(protocol.getMaxThreads() == 37 && protocol.getMinSpareThreads() == 3 && protocol.getMaxConnections() == 123 && protocol.getConnectionTimeout() == 4321, "thread pool connection count and timeout preserved");
                    check(protocol.getMaxHttpHeaderSize() == 65536, "HTTP header limit preserved");
                    check(factory.getSessionTimeout() == 73 && bound.getSession().getTimeout() == 73, "session timeout property remains unchanged");
                }
                String prefix = custom ? "/compat" : "";
                HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4));
                if (tls) {
                    SSLContext trustFixture = SSLContext.getInstance("TLSv1.2");
                    trustFixture.init(null, new TrustManager[]{new X509TrustManager() {
                        public void checkClientTrusted(X509Certificate[] chain, String auth) { }
                        public void checkServerTrusted(X509Certificate[] chain, String auth) { }
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    }}, new SecureRandom());
                    builder.sslContext(trustFixture);
                }
                HttpClient client = builder.build();
                String origin = (tls ? "https" : "http") + "://127.0.0.1:" + port;
                HttpResponse<byte[]> response = request(client, origin + prefix + "/probe?q=%C3%A8", true, "AllowedFixture/1", null, null);
                check(response.statusCode() == 200 && body(response).equals(BODY), "real HTTP response URI decoding and context path");
                check(response.headers().firstValue("content-encoding").orElse("").equals(compressed ? "gzip" : ""), "actual gzip matches standard compression property");
                check("standard-binding".equals(DETAILS.get("contextParameter")), "context parameters initializer preserved");
                if (compressed) {
                    check(!request(client, origin + prefix + "/probe?q=%C3%A8", true, "ExcludedFixture/1", null, null).headers().firstValue("content-encoding").isPresent(), "compression excluded user-agent preserved");
                    check(!request(client, origin + prefix + "/binary?q=%C3%A8", true, "AllowedFixture/1", null, null).headers().firstValue("content-encoding").isPresent(), "compression MIME selection preserved");
                    check(!request(client, origin + prefix + "/small", true, "AllowedFixture/1", null, null).headers().firstValue("content-encoding").isPresent(), "compression minimum size preserved");
                }
                if (custom) {
                    check(response.headers().firstValue("server").orElse("").equals("Boot15Tomcat9Fixture"), "server response header preserved");
                    check(request(client, origin + "/probe?q=%C3%A8", false, "AllowedFixture/1", null, null).statusCode() == 404, "context path enforced");
                    check(request(client, origin + prefix + "/small", false, "AllowedFixture/1", null, "x".repeat(66000)).statusCode() == 400, "oversized header rejected by actual connector");
                }
                check(HttpServletRequest.class.getMethod("getHttpServletMapping").getReturnType().getName().equals("javax.servlet.http.HttpServletMapping"), "Servlet 4 API available without legacy shadowing");
                DETAILS.put("servletApiSource", HttpServletRequest.class.getProtectionDomain().getCodeSource().getLocation().toString());
                for (String method : Arrays.asList("GET", "PUT")) {
                    HttpRequest errorRequest = HttpRequest.newBuilder(URI.create(origin + prefix + "/fail")).timeout(Duration.ofSeconds(5)).method(method, HttpRequest.BodyPublishers.noBody()).build();
                    HttpResponse<String> errorResponse = client.send(errorRequest, HttpResponse.BodyHandlers.ofString());
                    check(errorResponse.statusCode() == 500 && errorResponse.body().equals("mapped-error"), "error page dispatch works for " + method);
                }
                HttpResponse<byte[]> session = request(client, origin + prefix + "/session", false, "AllowedFixture/1", null, null);
                String cookie = session.headers().firstValue("set-cookie").orElseThrow();
                check(cookie.startsWith((custom ? "BOOT15SID" : "JSESSIONID") + "="), "session cookie name");
                check(body(request(client, origin + prefix + "/session", false, "AllowedFixture/1", cookie.split(";", 2)[0], null)).startsWith("count=2"), "session continuity");
                if (custom) {
                    check(cookie.contains("HttpOnly") && cookie.contains("Path=/compat") && cookie.contains("Max-Age=120") && cookie.contains("Secure") == tls, "cookie path max-age secure and httponly standard binding");
                    DETAILS.put("actualSessionTimeoutSeconds", body(session));
                    check(body(session).contains("timeout=60"), "Boot whole-minute conversion of session timeout preserved");
                    check(Arrays.stream(container.getTomcat().getEngine().getPipeline().getValves()).anyMatch(v -> v instanceof AccessLogValve), "access log valve configured");
                    try (java.util.stream.Stream<Path> logs = Files.walk(base.resolve("audit"))) {
                        check(logs.filter(Files::isRegularFile).anyMatch(file -> { try { return Files.readString(file).contains("/compat/probe"); } catch (Exception e) { return false; } }), "real request emitted to access log");
                    }
                }
                if (tls) {
                    check(response.sslSession().orElseThrow().getProtocol().equals("TLSv1.2"), "TLS protocol and keystore standard properties");
                    check(bound.getSsl().getCiphers() == null == !name.equals("tls-custom"), "cipher configuration bean not mutated");
                    if (name.equals("tls-custom")) check(response.sslSession().orElseThrow().getCipherSuite().equals("TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"), "explicit cipher selection preserved");
                }
                verdict = "passed";
            } catch (Throwable failure) {
                failureText = failure.toString();
                failure.printStackTrace(System.out);
            } finally {
                if (context != null) context.close();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("case", name); row.put("verdict", verdict); row.put("checks", new ArrayList<>(CHECKS)); row.put("events", new ArrayList<>(EVENTS)); row.put("details", new LinkedHashMap<>(DETAILS)); row.put("failure", failureText);
                results.add(row);
                System.out.println("CASE_RESULT " + name + " " + verdict + " checks=" + CHECKS.size());
                allPassed &= verdict.equals("passed");
            }
        }
        Map<String, Object> output = new LinkedHashMap<>(); output.put("mode", mode); output.put("scope", "explicit Boot web auto-configuration, no TPS/MOS application scan"); output.put("results", results); output.put("deniedNonFixtureConnectionsAndDns", NETWORK.denied);
        new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(run.resolve("result.json").toFile(), output);
        System.exit(allPassed ? 0 : 1);
    }
    private static HttpResponse<byte[]> request(HttpClient client, String url, boolean gzip, String agent, String cookie, String largeHeader) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(6)).header("User-Agent", agent).GET();
        if (gzip) request.header("Accept-Encoding", "gzip");
        if (cookie != null) request.header("Cookie", cookie);
        if (largeHeader != null) request.header("X-Large-Fixture", largeHeader);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    private static String body(HttpResponse<byte[]> response) throws Exception {
        InputStream data = new ByteArrayInputStream(response.body());
        if (response.headers().firstValue("content-encoding").orElse("").equals("gzip")) data = new GZIPInputStream(data);
        return new String(data.readAllBytes(), StandardCharsets.UTF_8);
    }
}
