package com.cgbystrom.sockjs;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import com.cgbystrom.sockjs.test.BroadcastSession;
import com.codahale.metrics.JmxReporter;
import com.codahale.metrics.MetricRegistry;
import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.handler.codec.http.HttpChunkAggregator;
import org.jboss.netty.handler.codec.http.HttpRequestDecoder;
import org.jboss.netty.handler.codec.http.HttpResponseEncoder;

import org.jboss.netty.logging.InternalLoggerFactory;
import org.jboss.netty.logging.Slf4JLoggerFactory;
import org.junit.Ignore;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

import static org.jboss.netty.channel.Channels.pipeline;

@Ignore
public class TestServer {
    public static void main(String[] args) {
        Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        LoggerContext loggerContext = rootLogger.getLoggerContext();
        loggerContext.reset();
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(loggerContext);
        encoder.setPattern("%-5level %-20class{0}: %message%n");
        encoder.start();

        ConsoleAppender<ILoggingEvent> appender = new ConsoleAppender<ILoggingEvent>();
        appender.setContext(loggerContext);
        appender.setEncoder(encoder);
        appender.start();

        rootLogger.addAppender(appender);
        InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory());

        ServerBootstrap bootstrap = new ServerBootstrap(
                new NioServerSocketChannelFactory(
                        Executors.newCachedThreadPool(),
                        Executors.newCachedThreadPool()));

        final MetricRegistry registry = new MetricRegistry();
        final JmxReporter reporter = JmxReporter.forRegistry(registry).build();
        reporter.start();

        final ServiceRouter router = new ServiceRouter();
        router.setMetricRegistry(registry);

        router.registerService(new Service("/disabled_websocket_echo", new DisabledWebSocketEchoSession()));
        router.registerService(new Service("/close", new CloseSession()));
        router.registerService(new Service("/amplify", new AmplifySession()));
        router.registerService(new Service("/broadcast", new SessionCallbackFactory() {
            @Override
            public BroadcastSession getSession(String id) throws Exception {
                return new BroadcastSession();
            }
        }));

        Service echoService = new Service("/echo", new SessionCallbackFactory() {
            @Override
            public EchoSession getSession(String id) throws Exception {
                return new EchoSession();
            }
        });
        echoService.setMaxResponseSize(4096);
        router.registerService(echoService);

        Service cookieNeededEcho = new Service("/cookie_needed_echo", new EchoSession());
        cookieNeededEcho.setMaxResponseSize(4096);
        cookieNeededEcho.setCookieNeeded(true);
        router.registerService(cookieNeededEcho);

        bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
            @Override
            public ChannelPipeline getPipeline() throws Exception {
                ChannelPipeline pipeline = pipeline();
                pipeline.addLast("decoder", new HttpRequestDecoder());
                pipeline.addLast("chunkAggregator", new HttpChunkAggregator(130 * 1024)); // Required for WS handshaker or else NPE.
                pipeline.addLast("encoder", new HttpResponseEncoder());
                pipeline.addLast("preflight", new PreflightHandler());
                pipeline.addLast("router", router);
                return pipeline;
            }
        });

        bootstrap.bind(new InetSocketAddress(8090));
        System.out.println("Server running..");
    }
}
