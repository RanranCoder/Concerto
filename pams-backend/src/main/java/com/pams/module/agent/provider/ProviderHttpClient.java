package com.pams.module.agent.provider;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.impl.classic.*;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import java.net.*;

/** DNS approval happens inside connection establishment, preventing lookup/connect rebinding. */
@Component
public class ProviderHttpClient {
    private final CloseableHttpClient client;
    public ProviderHttpClient(ProviderUrlPolicy policy) { client = build(policy); }
    public CloseableHttpClient client() { return client; }
    public static CloseableHttpClient build(ProviderUrlPolicy policy) {
        var pool = PoolingHttpClientConnectionManagerBuilder.create().setMaxConnTotal(32).setMaxConnPerRoute(8)
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(10)).setSocketTimeout(Timeout.ofSeconds(30)).build())
                .setDnsResolver(new DnsResolver() {
                    public InetAddress[] resolve(String host) throws UnknownHostException { return policy.resolve(host); }
                    public String resolveCanonicalHostname(String host) { return host; }
                }).build();
        return HttpClients.custom().setConnectionManager(pool).disableRedirectHandling().disableAutomaticRetries().disableCookieManagement().build();
    }
    @PreDestroy public void close() throws Exception { client.close(); }
}
