package info.bitrich.xchangestream.cexio;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import info.bitrich.xchangestream.cexio.dto.*;
import info.bitrich.xchangestream.core.StreamingPrivateDataService;
import info.bitrich.xchangestream.service.netty.JsonNettyStreamingService;
import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import org.knowm.xchange.dto.Order;
import org.knowm.xchange.exceptions.ExchangeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class CexioStreamingPrivateDataRawService extends JsonNettyStreamingService
        implements StreamingPrivateDataService {

    private static final Logger LOG = LoggerFactory.getLogger(CexioStreamingPrivateDataRawService.class);

    public static final String CONNECTED = "connected";
    public static final String AUTH = "auth";
    public static final String PING = "ping";
    public static final String PONG = "pong";
    public static final String ORDER = "order";
    public static final String TRANSACTION = "tx";

    private String apiKey;
    private String apiSecret;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PublishSubject<Boolean> subjectConnected = PublishSubject.create();
    private PublishSubject<Order> subjectOrder = PublishSubject.create();
    private PublishSubject<CexioWebSocketTransaction> subjectTransaction = PublishSubject.create();

    public CexioStreamingPrivateDataRawService(String apiUrl) {
        super(apiUrl, Integer.MAX_VALUE);
    }

    @Override
    public Observable<Order> getOrders() {
        return subjectOrder.share();
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public void setApiSecret(String apiSecret) {
        this.apiSecret = apiSecret;
    }

    public Observable<CexioWebSocketTransaction> getTransactions() {
        return subjectTransaction.share();
    }

    @Override
    public Completable connect() {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("Private API requires for API key");
        }
        if (apiSecret == null || apiSecret.isEmpty()) {
            throw new IllegalArgumentException("Private API requires for API secret key");
        }

        return super.connect();
    }

    /**
     * Triggers at connect to stream or at reconnect case
     *
     */
    public Observable<Boolean> isConnected() {
        return subjectConnected.share();
    }

    @Override
    public String getSubscribeMessage(String channelName, Object... args) throws IOException {
        return null;
    }

    @Override
    public String getUnsubscribeMessage(String channelName) throws IOException {
        return null;
    }

    @Override
    public void messageHandler(String message) {
        JsonNode jsonNode;
        try {
            jsonNode = objectMapper.readTree(message);
        } catch (IOException e) {
            LOG.error("Error parsing incoming message to JSON: {}", message);
            subjectOrder.onError(e);
            return;
        }
        handleMessage(jsonNode);
    }

    @Override
    protected void handleMessage(JsonNode message) {
        LOG.debug("Receiving message: {}", message);
        JsonNode cexioMessage = message.get("e");

        try {
            if (cexioMessage != null) {
                switch (cexioMessage.textValue()) {
                    case CONNECTED:
                        auth();
                        break;
                    case AUTH:
                        CexioWebSocketAuthResponse response = deserialize(message, CexioWebSocketAuthResponse.class);
                        if (response != null && !response.isSuccess()) {
                            String error = String.format("Authentication error: %s", response.getData().getError());
                            LOG.error(error);

                            ExchangeException exception = new ExchangeException(error);
                            subjectOrder.onError(exception);
                            subjectTransaction.onError(exception);
                        }
                        subjectConnected.onNext(true);
                        break;
                    case PING:
                        pong();
                        break;
                    case ORDER:
                        try {
                            CexioWebSocketOrderMessage cexioOrder =
                                    deserialize(message, CexioWebSocketOrderMessage.class);
                            Order order = CexioAdapters.adaptOrder(cexioOrder.getData());
                            LOG.debug(String.format("Order is updated: %s", order));
                            subjectOrder.onNext(order);
                        } catch (Exception e) {
                            LOG.error("Order parsing error: {}", e.getMessage(), e);
                            subjectOrder.onError(e);
                        }
                        break;
                    case TRANSACTION:
                        try {
                            CexioWebSocketTransactionMessage transaction =
                                    deserialize(message, CexioWebSocketTransactionMessage.class);
                            LOG.debug(String.format("New transaction: %s", transaction.getData()));
                            subjectTransaction.onNext(transaction.getData());
                        } catch (Exception e) {
                            LOG.error("Transaction parsing error: {}", e.getMessage(), e);
                            subjectTransaction.onError(e);
                        }
                        break;
                }
            }
        } catch (JsonProcessingException e) {
            LOG.error("Json parsing error: {}", e.getMessage());
        }
    }

    @Override
    protected String getChannelNameFromMessage(JsonNode message) throws IOException {
        return null;
    }

    private void auth() {
        long timestamp = System.currentTimeMillis() / 1000;
        CexioDigest cexioDigest = CexioDigest.createInstance(apiSecret);
        String signature = cexioDigest.createSignature(timestamp, apiKey);
        CexioWebSocketAuthMessage message = new CexioWebSocketAuthMessage(
                new CexioWebSocketAuth(apiKey, signature, timestamp));
        sendMessage(message);
    }

    private void pong() {
        CexioWebSocketPongMessage message = new CexioWebSocketPongMessage();
        sendMessage(message);
    }

    private void sendMessage(Object message) {
        try {
            sendMessage(objectMapper.writeValueAsString(message));
        } catch (JsonProcessingException e) {
            LOG.error("Error creating json message: {}", e.getMessage());
        }
    }

    private <T> T deserialize(JsonNode message, Class<T> valueType) throws JsonProcessingException {
        return objectMapper.treeToValue(message, valueType);
    }
}
