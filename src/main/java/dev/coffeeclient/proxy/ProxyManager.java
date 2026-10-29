package dev.coffeeclient.proxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.coffeeclient.account.Vault;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.util.JsonFiles;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.proxy.HttpProxyHandler;
import io.netty.handler.proxy.ProxyHandler;
import io.netty.handler.proxy.Socks4ProxyHandler;
import io.netty.handler.proxy.Socks5ProxyHandler;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Saved proxies ({@code proxies.json}) and the one in use. While one is active, every connection to a
 * server (joining and the server list's pings) goes through it: ConnectionMixin puts its netty handler
 * first in the pipeline (after proxies). Server names are still looked up locally.
 */
public final class ProxyManager {
	/** Where Test connects through the proxy. */
	private static final String TEST_HOST = "api.minecraftservices.com";
	private static final int TEST_PORT = 443;
	private static final int TIMEOUT_MS = 8000;

	private final Path file;
	private final List<Proxy> proxies = new ArrayList<>();
	/** Sealed passwords the vault key cannot open, by proxy: saved back unchanged instead of as empty. */
	private final Map<Proxy, String> unreadablePasswords = new HashMap<>();
	private volatile Proxy active;
	private int revision;

	public ProxyManager(Path file) {
		this.file = file;
	}

	public int revision() {
		return revision;
	}

	public List<Proxy> all() {
		return List.copyOf(proxies);
	}

	/** The proxy connections go through, or null for a direct connection. */
	public Proxy active() {
		return active;
	}

	public void setActive(Proxy proxy) {
		active = proxy != null && proxies.contains(proxy) ? proxy : null;
		changed();
	}

	public Proxy add(Proxy proxy) {
		proxies.add(proxy);
		changed();
		return proxy;
	}

	public void remove(Proxy proxy) {
		if (!proxies.remove(proxy)) return;
		unreadablePasswords.remove(proxy);
		if (active == proxy) active = null;
		changed();
	}

	/** Call after editing a proxy's fields. */
	public void edited() {
		changed();
	}

	/** A fresh netty handler for the active proxy, or null (ConnectionMixin, netty thread). */
	public ProxyHandler handler() {
		Proxy proxy = active;
		return proxy == null ? null : handler(proxy);
	}

	private static ProxyHandler handler(Proxy proxy) {
		InetSocketAddress address = new InetSocketAddress(proxy.host, proxy.port);
		boolean auth = !proxy.username.isEmpty();
		ProxyHandler handler = switch (proxy.type) {
			case SOCKS5 -> auth ? new Socks5ProxyHandler(address, proxy.username, proxy.password) : new Socks5ProxyHandler(address);
			case SOCKS4 -> auth ? new Socks4ProxyHandler(address, proxy.username) : new Socks4ProxyHandler(address);
			case HTTP -> auth ? new HttpProxyHandler(address, proxy.username, proxy.password) : new HttpProxyHandler(address);
		};
		handler.setConnectTimeoutMillis(TIMEOUT_MS);
		return handler;
	}

	/**
	 * Connects through {@code proxy} to the Minecraft services and back. Completes with the round trip
	 * in ms, or exceptionally with the reason; also stored on the proxy.
	 */
	public CompletableFuture<Long> test(Proxy proxy) {
		CompletableFuture<Long> result = new CompletableFuture<>();
		EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
		long start = System.nanoTime();
		ProxyHandler handler;
		try {
			handler = handler(proxy);
		} catch (RuntimeException e) {
			group.shutdownGracefully();
			result.completeExceptionally(e);
			return finish(proxy, result);
		}
		new Bootstrap().group(group).channel(NioSocketChannel.class)
			.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, TIMEOUT_MS)
			.handler(new ChannelInitializer<SocketChannel>() {
				@Override
				protected void initChannel(SocketChannel channel) {
					channel.pipeline().addFirst(handler).addLast(new ChannelInboundHandlerAdapter() {
						@Override
						public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
							result.completeExceptionally(cause);
							ctx.close();
						}
					});
				}
			})
			.connect(InetSocketAddress.createUnresolved(TEST_HOST, TEST_PORT))
			.addListener(f -> {
				if (!f.isSuccess()) result.completeExceptionally(f.cause());
			});
		handler.connectFuture().addListener(f -> {
			if (f.isSuccess()) {
				result.complete((System.nanoTime() - start) / 1_000_000);
				((io.netty.channel.Channel) f.getNow()).close();
			} else {
				result.completeExceptionally(f.cause());
			}
		});
		result.orTimeout(TIMEOUT_MS + 2000, TimeUnit.MILLISECONDS).whenComplete((ms, error) -> group.shutdownGracefully());
		return finish(proxy, result);
	}

	private static CompletableFuture<Long> finish(Proxy proxy, CompletableFuture<Long> result) {
		return result.whenComplete((ms, error) -> {
			proxy.latency = error == null ? Math.max(1, ms) : -1;
			proxy.lastError = error == null ? "" : reason(error);
		});
	}

	private static String reason(Throwable error) {
		Throwable cause = error;
		while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
		String message = cause.getMessage();
		return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
	}

	private void changed() {
		revision++;
		save();
	}

	public void load() {
		JsonObject root = JsonFiles.read(file);
		if (root == null || !(root.get("proxies") instanceof JsonArray array)) return;
		int activeIndex = root.has("active") ? root.get("active").getAsInt() : -1;
		for (JsonElement element : array) {
			try {
				JsonObject o = element.getAsJsonObject();
				String sealed = o.has("password") ? o.get("password").getAsString() : "";
				String password = Vault.open(sealed);
				Proxy proxy = new Proxy(o.has("name") ? o.get("name").getAsString() : "",
					Proxy.Type.valueOf(o.get("type").getAsString().toUpperCase(Locale.ROOT)),
					o.get("host").getAsString(), o.get("port").getAsInt(),
					o.has("username") ? o.get("username").getAsString() : "", password == null ? "" : password);
				if (password == null) unreadablePasswords.put(proxy, sealed);
				proxies.add(proxy);
			} catch (RuntimeException e) {
				CoffeeClient.LOG.warn("[Proxies] Skipping a bad entry: {}", e.toString());
			}
		}
		active = activeIndex >= 0 && activeIndex < proxies.size() ? proxies.get(activeIndex) : null;
		revision++;
	}

	private void save() {
		JsonArray array = new JsonArray();
		for (Proxy p : proxies) {
			JsonObject o = new JsonObject();
			o.addProperty("name", p.name);
			o.addProperty("type", p.type.name());
			o.addProperty("host", p.host);
			o.addProperty("port", p.port);
			o.addProperty("username", p.username);
			// Until a new password is typed in, one the key cannot open is kept as it was.
			String sealed = unreadablePasswords.get(p);
			o.addProperty("password", sealed != null && p.password.isEmpty() ? sealed : Vault.seal(p.password));
			array.add(o);
		}
		JsonObject root = new JsonObject();
		root.addProperty("version", 1);
		root.addProperty("active", active == null ? -1 : proxies.indexOf(active));
		root.add("proxies", array);
		try {
			JsonFiles.writeAtomic(file, JsonFiles.GSON.toJson(root));
		} catch (Exception e) {
			CoffeeClient.LOG.warn("[Proxies] Could not save proxies: {}", e.toString());
		}
	}
}
