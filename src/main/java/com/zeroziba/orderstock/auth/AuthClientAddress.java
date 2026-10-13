package com.zeroziba.orderstock.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

public class AuthClientAddress {
    public static final String HEADER="X-Stockroom-Client-IP";
    private final List<IpAddressMatcher> proxies;
    public AuthClientAddress(AuthLimitProperties properties) {
        proxies=properties.trustedProxies().stream().map(String::strip).map(IpAddressMatcher::new).toList();
    }
    public String resolve(HttpServletRequest request) {
        String peer=request.getRemoteAddr();
        if (proxies.stream().anyMatch(proxy->proxy.matches(peer))) {
            String client=request.getHeader(HEADER);
            // One numeric address only; never resolve a caller-controlled hostname.
            if (client!=null && client.length()<=45) {
                if (client.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
                    String[] octets=client.split("\\.");
                    if (java.util.Arrays.stream(octets).mapToInt(Integer::parseInt).allMatch(value->value<=255))
                        return java.util.Arrays.stream(octets).map(value->Integer.toString(Integer.parseInt(value)))
                                .collect(java.util.stream.Collectors.joining("."));
                }
                // A colon and literal-only characters ensure InetAddress never resolves DNS.
                try { if (client.contains(":") && client.matches("[0-9a-fA-F:.]+"))
                    return InetAddress.getByName(client).getHostAddress(); }
                catch (UnknownHostException ignored) { /* Invalid proxy metadata shares the peer's quota. */ }
            }
        }
        return peer;
    }
}
