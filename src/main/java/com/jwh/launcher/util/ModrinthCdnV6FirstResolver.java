package com.jwh.launcher.util;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.stream.Stream;

/**
 * 按主机定制 DNS 解析顺序的 SPI Provider。
 *
 * 实测（2026-11-03，用户网络）：api.modrinth.com（搜索/API）只有 IPv4 可达（IPv6 连接超时），
 * 而 cdn.modrinth.com（大文件下载 CDN）IPv6 路由更优。JVM 全局 preferIPv6Addresses 无法按主机
 * 区分（169 版全局开 IPv6 导致 Mod/材质包/光影包/整合包搜索全部 HTTP 超时）。
 *
 * 本 Provider 只对 Modrinth 下载 CDN 域名把 IPv6 排到最前（HttpClient 连接时取解析结果的
 * 第一个地址），其余域名保持 JVM 默认（IPv4 优先），从而搜索正常、下载走 IPv6 快路径。
 */
public class ModrinthCdnV6FirstResolver extends InetAddressResolverProvider {

    /** 命中该后缀的域名：IPv6 优先。仅 Modrinth 文件下载 CDN。 */
    static boolean isV6FirstHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase();
        return h.equals("cdn.modrinth.com") || h.endsWith(".cdn.modrinth.com");
    }

    @Override
    public InetAddressResolver get(Configuration configuration) {
        InetAddressResolver delegate = configuration.builtinResolver();
        return new InetAddressResolver() {
            @Override
            public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy)
                    throws UnknownHostException {
                Stream<InetAddress> base = delegate.lookupByName(host, lookupPolicy);
                if (isV6FirstHost(host)) {
                    return base.sorted((a, b) -> {
                        boolean a6 = a instanceof Inet6Address;
                        boolean b6 = b instanceof Inet6Address;
                        if (a6 && !b6) return -1;
                        if (!a6 && b6) return 1;
                        return 0;
                    });
                }
                return base;
            }

            @Override
            public String lookupByAddress(byte[] addr) throws UnknownHostException {
                return delegate.lookupByAddress(addr);
            }
        };
    }

    @Override
    public String name() {
        return "jwh-modrinth-cdn-v6-first";
    }
}
