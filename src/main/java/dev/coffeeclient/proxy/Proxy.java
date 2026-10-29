package dev.coffeeclient.proxy;

/** A saved proxy. The password is encrypted on disk (see {@link dev.coffeeclient.account.Vault}). */
public final class Proxy {
	public enum Type {
		SOCKS5("SOCKS5"), SOCKS4("SOCKS4"), HTTP("HTTP");

		public final String label;

		Type(String label) {
			this.label = label;
		}
	}

	public String name;
	public Type type;
	public String host;
	public int port;
	public String username;
	public String password;
	/** Result of the last test: round trip in ms, -1 = failed, 0 = not tested. */
	public volatile long latency;
	public volatile String lastError = "";

	public Proxy(String name, Type type, String host, int port, String username, String password) {
		this.name = name;
		this.type = type;
		this.host = host;
		this.port = port;
		this.username = username == null ? "" : username;
		this.password = password == null ? "" : password;
	}

	public String address() {
		return host + ":" + port;
	}

	public String displayName() {
		return name == null || name.isBlank() ? address() : name;
	}
}
