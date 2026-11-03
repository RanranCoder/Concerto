package com.pams.module.agent.provider;

import com.pams.common.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.*;
import java.util.*;

@Component
public class ProviderUrlPolicy {
    private final Set<String> internalHosts;
    public ProviderUrlPolicy(@Value("${pams.agent.provider-internal-hosts:}") String hosts) {
        internalHosts = new HashSet<>(); for (String host : hosts.split(",")) if (!host.isBlank()) internalHosts.add(host.trim().toLowerCase(Locale.ROOT));
    }
    public String validate(String value) {
        try {
            URI uri = URI.create(value.trim().replaceAll("/+$", ""));
            String host = uri.getHost(); boolean internal = host != null && internalHosts.contains(host.toLowerCase(Locale.ROOT));
            if (host == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !("https".equals(uri.getScheme()) || internal && "http".equals(uri.getScheme()))
                    || uri.getPath().endsWith("/chat/completions") || uri.getPath().endsWith("/responses")) throw new IllegalArgumentException();
            resolve(host);
            return uri.toString();
        } catch (Exception e) { throw new BizException(4400, "Base URL 无效或网络目标不允许；请填写 HTTPS API 根地址，内网服务需部署白名单"); }
    }
    /** Resolve once at connection time; the transport connects to these exact approved addresses. */
    public InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (!internalHosts.contains(host.toLowerCase(Locale.ROOT))) for (InetAddress address : addresses) {
                byte[] b = address.getAddress(); int first = b[0] & 255, second = b.length > 1 ? b[1] & 255 : 0;
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                        || address.isMulticastAddress() || b.length == 16 && (first & 254) == 252
                        || b.length == 4 && (first == 0 || first >= 224 || first == 100 && second >= 64 && second <= 127 || first == 169 && second == 254))
                    throw new UnknownHostException("AI 网络目标不允许");
        }
        return addresses;
    }
}
