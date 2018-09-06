package info.bitrich.xchangestream.service.netty;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.reactivex.disposables.Disposable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import info.bitrich.xchangestream.service.exception.NotConnectedException;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketClientExtensionHandler;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketClientCompressionHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.subjects.BehaviorSubject;

public abstract class NettyStreamingService<T> {
    private final Logger LOG = LoggerFactory.getLogger(this.getClass());
    private static final Duration DEFAULT_CONNECTION_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_RETRY_DURATION = Duration.ofSeconds(15);

    private Disposable resubscribeDisposable;

    private class Subscription {
        final ObservableEmitter<T> emitter;
        final String channelName;
        final Object[] args;

        public Subscription(ObservableEmitter<T> emitter, String channelName, Object[] args) {
            this.emitter = emitter;
            this.channelName = channelName;
            this.args = args;
        }
    }

    private final int maxFramePayloadLength;
    private final URI uri;
    private boolean isManualDisconnect = false;
    private Channel webSocketChannel;
    private Duration retryDuration;
    private Duration connectionTimeout;
    private NioEventLoopGroup eventLoopGroup;
    protected Map<String, Subscription> channels = new ConcurrentHashMap<>();
    private boolean compressedMessages = false;
    private BehaviorSubject<Boolean> connectedSubject = BehaviorSubject.createDefault(false);

    public NettyStreamingService(String apiUrl) {
        this(apiUrl, 65536);
    }

    public NettyStreamingService(String apiUrl, int maxFramePayloadLength) {
        this(apiUrl, maxFramePayloadLength, DEFAULT_CONNECTION_TIMEOUT, DEFAULT_RETRY_DURATION);
    }

    public NettyStreamingService(String apiUrl, int maxFramePayloadLength, Duration connectionTimeout, Duration retryDuration) {
        try {
            this.maxFramePayloadLength = maxFramePayloadLength;
            this.retryDuration = retryDuration;
            this.connectionTimeout = connectionTimeout;
            this.uri = new URI(apiUrl);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Error parsing URI " + apiUrl, e);
        }
    }

    public Completable connect() {
        isManualDisconnect = false;

        return Completable.create(completable -> {
            try {
                LOG.info("Connecting to {}://{}:{}{}", uri.getScheme(), uri.getHost(), uri.getPort(), uri.getPath());
                String scheme = uri.getScheme() == null ? "ws" : uri.getScheme();

                String host = uri.getHost();
                if (host == null) {
                    throw new IllegalArgumentException("Host cannot be null.");
                }

                final int port;
                if (uri.getPort() == -1) {
                    if ("ws".equalsIgnoreCase(scheme)) {
                        port = 80;
                    } else if ("wss".equalsIgnoreCase(scheme)) {
                        port = 443;
                    } else {
                        port = -1;
                    }
                } else {
                    port = uri.getPort();
                }

                if (!"ws".equalsIgnoreCase(scheme) && !"wss".equalsIgnoreCase(scheme)) {
                    throw new IllegalArgumentException("Only WS(S) is supported.");
                }

                final boolean ssl = "wss".equalsIgnoreCase(scheme);
                final SslContext sslCtx;
                if (ssl) {
                    sslCtx = SslContextBuilder.forClient().build();
                } else {
                    sslCtx = null;
                }

                final WebSocketClientHandler handler = getWebSocketClientHandler(WebSocketClientHandshakerFactory.newHandshaker(
                        uri, WebSocketVersion.V13, null, true, new DefaultHttpHeaders(), maxFramePayloadLength),
                        this::messageHandler);

                Bootstrap b = new Bootstrap();
                eventLoopGroup = new NioEventLoopGroup();

                b.group(eventLoopGroup)
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, java.lang.Math.toIntExact(connectionTimeout.toMillis()))
                        .channel(NioSocketChannel.class)
                        .handler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel ch) {
                                ChannelPipeline p = ch.pipeline();
                                if (sslCtx != null) {
                                    p.addLast(sslCtx.newHandler(ch.alloc(), host, port));
                                }

                                WebSocketClientExtensionHandler clientExtensionHandler = getWebSocketClientExtensionHandler();
                                List<ChannelHandler> handlers = new ArrayList<>(4);
                                handlers.add(new HttpClientCodec());
                                if (compressedMessages) handlers.add(WebSocketClientCompressionHandler.INSTANCE);
                                handlers.add(new HttpObjectAggregator(65536));

                                if (clientExtensionHandler != null) {
                                    handlers.add(clientExtensionHandler);
                                }

                                handlers.add(handler);
                                p.addLast(handlers.toArray(new ChannelHandler[handlers.size()]));
                            }
                        });

                b.connect(uri.getHost(), port).addListener((ChannelFuture future) -> {
                    webSocketChannel = future.channel();
                    if (future.isSuccess()) {
                        handler.handshakeFuture().addListener(f -> {
                            if (f.isSuccess()) {
                                completable.onComplete();
                                connectedSubject.onNext(true);
                            } else {
                                completable.onError(f.cause());
                                connectedSubject.onNext(false);
                            }
                        });
                    } else {
                        completable.onError(future.cause());
                        connectedSubject.onNext(false);
                    }

                });
            } catch (Exception throwable) {
                completable.onError(throwable);
            }
        });
    }

    public Completable disconnect() {
        isManualDisconnect = true;
        return Completable.create(completable -> {

            Runnable cleanup = () -> {
                channels = new ConcurrentHashMap<>();
                completable.onComplete();
                connectedSubject.onNext(false);
                if (eventLoopGroup != null) {
                    eventLoopGroup.shutdownGracefully();
                }
            };

            if (webSocketChannel == null || !webSocketChannel.isOpen()) {
                cleanup.run();
                return;
            }

            CloseWebSocketFrame closeFrame = new CloseWebSocketFrame();
            webSocketChannel.writeAndFlush(closeFrame).addListener(future -> {
                cleanup.run();
            });
        });
    }

    public Observable<Boolean> connected() {
        return connectedSubject.distinctUntilChanged();
    }

    protected abstract String getChannelNameFromMessage(T message) throws IOException;

    public abstract String getSubscribeMessage(String channelName, Object... args) throws IOException;

    public abstract String getUnsubscribeMessage(String channelName) throws IOException;

    public String getSubscriptionUniqueId(String channelName, Object... args) {
        return channelName;
    }

    /**
     * Handler that receives incoming messages.
     *
     * @param message Content of the message from the server.
     */
    public abstract void messageHandler(String message);

    public void sendMessage(String message) {
        LOG.debug("Sending message: {}", message);

        if (webSocketChannel == null || !webSocketChannel.isOpen()) {
            LOG.warn("WebSocket is not open! Call connect first.");
            return;
        }

        if (!webSocketChannel.isWritable()) {
            LOG.warn("Cannot send data to WebSocket as it is not writable.");
            return;
        }

        if (message != null) {
            WebSocketFrame frame = new TextWebSocketFrame(message);
            webSocketChannel.writeAndFlush(frame);
        }
    }

    public Observable<T> subscribeChannel(String channelName, Object... args) {
        final String channelId = getSubscriptionUniqueId(channelName, args);
        LOG.info("Subscribing to channel {}", channelId);

        return Observable.<T>create(e -> {
            if (webSocketChannel == null || !webSocketChannel.isOpen()) {
                e.onError(new NotConnectedException());
            }

            if (!channels.containsKey(channelId)) {
                Subscription newSubscription = new Subscription(e, channelName, args);
                channels.put(channelId, newSubscription);
                try {
                    String message = getSubscribeMessage(channelName, args);
                    if (message != null) {
                        sendMessage(message);
                    }
                } catch (IOException throwable) {
                    e.onError(throwable);
                }
            }
        }).doOnDispose(() -> {
            if (channels.containsKey(channelId)) {
                String message = getUnsubscribeMessage(channelId);
                if (message != null) {
                    sendMessage(message);
                }
                channels.remove(channelId);
            }
        }).share();
    }

    public void resubscribeChannels() {
        for (String channelId : channels.keySet()) {
            try {
                Subscription subscription = channels.get(channelId);
                String message = getSubscribeMessage(subscription.channelName, subscription.args);
                if (message != null) {
                    sendMessage(message);
                }
            } catch (IOException e) {
                LOG.error("Failed to reconnect channel: {}", channelId);
            }
        }
    }

    public boolean isSocketOpen() {
        return webSocketChannel != null && webSocketChannel.isOpen();
    }

    public void useCompressedMessages(boolean compressedMessages) {
        this.compressedMessages = compressedMessages;
    }

    protected String getChannel(T message) {
        String channel;
        try {
            channel = getChannelNameFromMessage(message);
        } catch (IOException e) {
            LOG.error("Cannot parse channel from message: {}", message);
            return "";
        }
        return channel;
    }

    protected void handleMessage(T message) {
        String channel = getChannel(message);
        handleChannelMessage(channel, message);
    }

    protected void handleError(T message, Throwable t) {
        String channel = getChannel(message);
        handleChannelError(channel, t);
    }

    protected void handleChannelMessage(String channel, T message) {
        Subscription subscription = channels.get(channel);
        if (subscription == null) {
            LOG.debug("No subscription for channel {}.", channel);
            return;
        }

        ObservableEmitter<T> emitter = subscription.emitter;
        if (emitter == null) {
            LOG.debug("No subscriber for channel {}.", channel);
            return;
        }

        emitter.onNext(message);
    }

    protected void handleChannelError(String channel, Throwable t) {
        Subscription subscription = channels.get(channel);
        if (subscription == null) {
            LOG.debug("No subscription for channel {}.", channel);
            return;
        }

        ObservableEmitter<T> emitter = subscription.emitter;
        if (emitter == null) {
            LOG.debug("No subscriber for channel {}.", channel);
            return;
        }

        emitter.onError(t);
    }

    protected WebSocketClientExtensionHandler getWebSocketClientExtensionHandler() {
        return WebSocketClientCompressionHandler.INSTANCE;
    }

    protected WebSocketClientHandler getWebSocketClientHandler(WebSocketClientHandshaker handshaker,
                                                               WebSocketClientHandler.WebSocketMessageHandler handler) {
        return new NettyWebSocketClientHandler(handshaker, handler);
    }

    protected class NettyWebSocketClientHandler extends WebSocketClientHandler {
        protected NettyWebSocketClientHandler(WebSocketClientHandshaker handshaker, WebSocketMessageHandler handler) {
            super(handshaker, handler);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (!isManualDisconnect) {
                super.channelInactive(ctx);

                if (resubscribeDisposable != null && !resubscribeDisposable.isDisposed()) {
                    LOG.info("Connection closed by the host at reconnect");
                    return;
                }

                LOG.info("Reopening websocket because it was closed by the host");

                resubscribeDisposable = connect()
                        .doOnError(t -> LOG.warn("Problem with reconnect: {}", t.getMessage(), t))
                        .retryWhen(new RetryWithDelay(retryDuration.toMillis()))
                        .subscribe(() -> {
                            LOG.info("Resubscribing channels");
                            resubscribeChannels();
                        });
            }
        }
    }
}
