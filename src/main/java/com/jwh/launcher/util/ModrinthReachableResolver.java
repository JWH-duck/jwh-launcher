package com.jwh.launcher.util;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 按「实际可达性」为 Modrinth 域名择优 DNS 解析顺序的 SPI Provider。
 *
 * 背景：JDK HttpClient 不会像 curl 那样做 Happy Eyeballs 多地址回退——它只取解析结果的
 * 第一个地址建立连接，首个地址不可达就一直等到 connectTimeout。而 Cloudflare 双栈在不同
 * 网络/时段下可达性会反转：2026-11-03 实测 api.modrinth.com 仅 IPv4 可达；2026-11-24 实测
 * 反转为 IPv4 全超时、IPv6 秒通。静态把某一族排前，网络一反转搜索就再次全部 connect 超时。
 *
 * 本 Provider 对 Modrinth 的 API / CDN 域名：并行探测各地址 TCP 443 连通性，先连通的排到
 * 最前（HttpClient 取首地址即可直达可达链路），探测结果按主机缓存 5 分钟；全部不可达时保持
 * 系统原始顺序兜底。其余域名不做任何干预，保持 JVM 默认解析。
 */
public class ModrinthReachableResolver extends InetAddressResolverProvider {

    /** 单次 TCP 探测超时（毫秒）。 */
    private static final int PROBE_TIMEOUT_MS = 2500;
    /** 探测结果缓存时长（毫秒）：避免每次建连都探测，同时网络反转后能在缓存过期后自动跟上。 */
    private static final long CACHE_MILLIS = 5 * 60_000L;

    private static final ExecutorService PROBE_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "jwh-dns-probe");
        t.setDaemon(true);
        return t;
    });

    private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<>();

    private static final class CacheEntry {
        final List<InetAddress> ordered;
        final long expiresAt;

        CacheEntry(List<InetAddress> ordered, long expiresAt) {
            this.ordered = ordered;
            this.expiresAt = expiresAt;
        }
    }

    /** 命中该后缀的域名：做可达性探测择优。仅 Modrinth API 与文件下载 CDN。 */
    static boolean isProbedHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase();
        return h.equals("api.modrinth.com") || h.endsWith(".api.modrinth.com")
                || h.equals("cdn.modrinth.com") || h.endsWith(".cdn.modrinth.com");
    }

    @Override
    public InetAddressResolver get(Configuration configuration) {
        InetAddressResolver delegate = configuration.builtinResolver();
        return new InetAddressResolver() {
            @Override
            public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy)
                    throws UnknownHostException {
                List<InetAddress> base = delegate.lookupByName(host, lookupPolicy).toList();
                if (!isProbedHost(host) || base.size() <= 1) {
                    return base.stream();
                }
                long now = System.currentTimeMillis();
                CacheEntry cached = CACHE.get(host);
                if (cached != null && cached.expiresAt > now) {
                    return cached.ordered.stream();
                }
                List<InetAddress> ordered = probeOrder(base);
                CACHE.put(host, new CacheEntry(ordered, now + CACHE_MILLIS));
                return ordered.stream();
            }

            @Override
            public String lookupByAddress(byte[] addr) throws UnknownHostException {
                return delegate.lookupByAddress(addr);
            }
        };
    }

    /** 并行探测各地址 443 连通性；连通的排前，不可达的保持原顺序垫底。全不可达时返回原顺序。 */
    private static List<InetAddress> probeOrder(List<InetAddress> addrs) {
        List<CompletableFuture<InetAddress>> futures = new ArrayList<>();
        for (InetAddress a : addrs) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(a, 443), PROBE_TIMEOUT_MS);
                    return a;
                } catch (Exception e) {
                    return null;
                }
            }, PROBE_POOL));
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(PROBE_TIMEOUT_MS + 500, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // 个别地址探测超时：按已拿到的结果排序即可，不阻断整体
        }
        List<InetAddress> reachable = new ArrayList<>();
        List<InetAddress> unreachable = new ArrayList<>();
        for (int i = 0; i < addrs.size(); i++) {
            InetAddress addr = addrs.get(i);
            InetAddress r = null;
            try {
                r = futures.get(i).getNow(null);
            } catch (Exception ignored) {
                r = null;
            }
            (r != null ? reachable : unreachable).add(addr);
        }
        List<InetAddress> out = new ArrayList<>(reachable);
        out.addAll(unreachable);
        return out;
    }

    @Override
    public String name() {
        return "jwh-modrinth-reachable";
    }
}
