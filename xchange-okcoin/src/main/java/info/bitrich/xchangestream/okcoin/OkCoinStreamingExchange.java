package info.bitrich.xchangestream.okcoin;

import info.bitrich.xchangestream.core.ProductSubscription;
import info.bitrich.xchangestream.core.StreamingExchange;
import info.bitrich.xchangestream.core.StreamingMarketDataService;
import info.bitrich.xchangestream.core.StreamingPrivateDataService;
import io.reactivex.Completable;
import org.knowm.xchange.exceptions.NotAvailableFromExchangeException;
import org.knowm.xchange.okcoin.OkCoinExchange;

public class OkCoinStreamingExchange extends OkCoinExchange implements StreamingExchange {
    private static final String API_URI = "wss://real.okcoin.com:10440/websocket";

    private final OkCoinStreamingService streamingService;
    private OkCoinStreamingMarketDataService streamingMarketDataService;

    public OkCoinStreamingExchange() {
        streamingService = new OkCoinStreamingService(API_URI);
    }

    protected OkCoinStreamingExchange(OkCoinStreamingService streamingService) {
        this.streamingService = streamingService;
    }

    @Override
    protected void initServices() {
        super.initServices();
        streamingMarketDataService = new OkCoinStreamingMarketDataService(streamingService);
    }

    @Override
    public Completable connect(ProductSubscription... args) {
        return streamingService.connect();
    }

    @Override
    public Completable disconnect() {
        return streamingService.disconnect();
    }

    @Override
    public boolean isAlive() {
        return streamingService.isSocketOpen();
    }

    @Override
    public StreamingMarketDataService getStreamingMarketDataService() {
        return streamingMarketDataService;
    }

    @Override
    public StreamingPrivateDataService getStreamingPrivateDataService() {
        throw new NotAvailableFromExchangeException();
    }

    @Override
    public void useCompressedMessages(boolean compressedMessages) {
        streamingService.useCompressedMessages(compressedMessages);
    }

    @Override
    public void close() throws Exception {
        streamingService.close();
    }
}
