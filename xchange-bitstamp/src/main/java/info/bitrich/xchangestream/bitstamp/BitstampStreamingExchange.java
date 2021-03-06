package info.bitrich.xchangestream.bitstamp;

import info.bitrich.xchangestream.core.ProductSubscription;
import info.bitrich.xchangestream.core.StreamingExchange;
import info.bitrich.xchangestream.core.StreamingMarketDataService;
import info.bitrich.xchangestream.core.StreamingPrivateDataService;
import info.bitrich.xchangestream.service.pusher.PusherStreamingService;
import io.reactivex.Completable;
import io.reactivex.Observable;
import org.knowm.xchange.bitstamp.BitstampExchange;
import org.knowm.xchange.exceptions.NotAvailableFromExchangeException;

public class BitstampStreamingExchange extends BitstampExchange implements StreamingExchange {
    private static final String API_KEY = "de504dc5763aeef9ff52";
    private final PusherStreamingService streamingService;

    private BitstampStreamingMarketDataService streamingMarketDataService;

    public BitstampStreamingExchange() {
        streamingService = new PusherStreamingService(API_KEY);
    }

    @Override
    protected void initServices() {
        super.initServices();
        streamingMarketDataService = new BitstampStreamingMarketDataService(streamingService);
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
    public StreamingMarketDataService getStreamingMarketDataService() {
        return streamingMarketDataService;
    }

    @Override
    public StreamingPrivateDataService getStreamingPrivateDataService() {
        throw new NotAvailableFromExchangeException();
    }

    @Override
    public boolean isAlive() {
        return this.streamingService.isSocketOpen();
    }

    public Observable<Boolean> ready() {
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void useCompressedMessages(boolean compressedMessages) {
        streamingService.useCompressedMessages(compressedMessages);
    }
}
