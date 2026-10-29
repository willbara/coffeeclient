package dev.coffeeclient.module.modules.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.StringSetting;
import net.minecraft.client.Minecraft;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Discord Rich Presence over Discord's local IPC socket (no extra libraries). Needs an Application ID
 * from discord.com/developers/applications; everything runs on its own background thread.
 */
public final class DiscordPresence extends Module {
	public final StringSetting appId = setting(new StringSetting("Application ID", "Your Discord application's ID (Developer Portal)", "", 24));
	public final StringSetting details = setting(new StringSetting("Details", "First line of the presence", "Playing Minecraft", 64));
	public final BooleanSetting showServer = setting(new BooleanSetting("Show server", "Second line shows the server (or Singleplayer)", false));
	public final BooleanSetting showDimension = setting(new BooleanSetting("Show dimension", "Second line shows the dimension", true));
	public final BooleanSetting showElapsed = setting(new BooleanSetting("Elapsed time", "Show time since joining", true));

	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Coffee Discord IPC");
		t.setDaemon(true);
		return t;
	});
	private Ipc ipc;
	private long startEpoch = System.currentTimeMillis() / 1000;
	private int ticks;
	private String lastState = "";

	public DiscordPresence() {
		super("DiscordPresence", Category.CLIENT, "Shows what you are playing in Discord.");
	}

	@Override
	protected void onEnable() {
		if (appId.get().isBlank()) {
			notify("Set an Application ID in its settings first");
			return;
		}
		startEpoch = System.currentTimeMillis() / 1000;
		lastState = "";
		ticks = 0;
	}

	@Override
	protected void onDisable() {
		worker.execute(this::close);
	}

	@Override
	public void onTick() {
		if (appId.get().isBlank() || ++ticks % 100 != 1) return;
		String state = state();
		String detail = details.get();
		if (state.equals(lastState) && ipc != null) return;
		lastState = state;
		String id = appId.get().trim();
		long start = showElapsed.get() ? startEpoch : 0;
		worker.execute(() -> send(id, detail, state, start));
	}

	private String state() {
		Minecraft mc = mc();
		List<String> parts = new ArrayList<>();
		if (showServer.get() && mc.level != null) {
			var server = mc.getCurrentServer();
			parts.add(mc.getSingleplayerServer() != null ? "Singleplayer" : StreamerMode.hideServer() || server == null ? "Multiplayer" : server.ip);
		}
		if (showDimension.get() && mc.level != null) {
			String path = mc.level.dimension().identifier().getPath().replace('_', ' ');
			parts.add(Character.toUpperCase(path.charAt(0)) + path.substring(1));
		}
		if (mc.level == null) parts.add("In menus");
		return String.join(" · ", parts);
	}

	private void send(String clientId, String detail, String state, long start) {
		try {
			if (ipc == null) ipc = Ipc.connect(clientId);
			JsonObject activity = new JsonObject();
			if (!detail.isBlank()) activity.addProperty("details", detail);
			if (!state.isBlank()) activity.addProperty("state", state);
			if (start > 0) {
				JsonObject ts = new JsonObject();
				ts.addProperty("start", start);
				activity.add("timestamps", ts);
			}
			ipc.setActivity(activity);
		} catch (Exception e) {
			close();
			CoffeeClient.LOG.debug("Discord IPC unavailable: {}", e.toString());
		}
	}

	private void close() {
		if (ipc == null) return;
		try {
			ipc.setActivity(null);
		} catch (Exception ignored) {
			// closing anyway
		}
		ipc.close();
		ipc = null;
	}

	/** Minimal Discord IPC client: framed JSON (little-endian opcode + length) over a socket/pipe. */
	private static final class Ipc implements Closeable {
		private final SocketChannel socket;
		private final RandomAccessFile pipe;

		private Ipc(SocketChannel socket, RandomAccessFile pipe) {
			this.socket = socket;
			this.pipe = pipe;
		}

		static Ipc connect(String clientId) throws IOException {
			Ipc ipc = open();
			JsonObject hello = new JsonObject();
			hello.addProperty("v", 1);
			hello.addProperty("client_id", clientId);
			ipc.write(0, hello.toString());
			ipc.read();
			return ipc;
		}

		private static Ipc open() throws IOException {
			if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
				for (int i = 0; i < 10; i++) {
					try {
						return new Ipc(null, new RandomAccessFile("\\\\.\\pipe\\discord-ipc-" + i, "rw"));
					} catch (IOException ignored) {
						// try next
					}
				}
				throw new IOException("Discord is not running");
			}
			List<Path> roots = new ArrayList<>();
			for (String env : new String[] {"XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"}) {
				String v = System.getenv(env);
				if (v != null) roots.add(Path.of(v));
			}
			roots.add(Path.of("/tmp"));
			String[] subdirs = {"", "app/com.discordapp.Discord", "snap.discord", "app/dev.vencord.Vesktop"};
			for (Path root : roots) {
				for (String sub : subdirs) {
					for (int i = 0; i < 10; i++) {
						Path p = (sub.isEmpty() ? root : root.resolve(sub)).resolve("discord-ipc-" + i);
						if (!Files.exists(p)) continue;
						try {
							return new Ipc(SocketChannel.open(UnixDomainSocketAddress.of(p)), null);
						} catch (IOException ignored) {
							// try next
						}
					}
				}
			}
			throw new IOException("Discord is not running");
		}

		void setActivity(JsonObject activity) throws IOException {
			JsonObject args = new JsonObject();
			args.addProperty("pid", ProcessHandle.current().pid());
			if (activity != null) args.add("activity", activity);
			JsonObject cmd = new JsonObject();
			cmd.addProperty("cmd", "SET_ACTIVITY");
			cmd.add("args", args);
			cmd.addProperty("nonce", UUID.randomUUID().toString());
			write(1, cmd.toString());
			JsonObject reply = read();
			if (reply != null && "ERROR".equals(reply.has("evt") && !reply.get("evt").isJsonNull() ? reply.get("evt").getAsString() : "")) {
				throw new IOException("Discord rejected the presence: " + reply);
			}
		}

		private void write(int op, String json) throws IOException {
			byte[] payload = json.getBytes(StandardCharsets.UTF_8);
			ByteBuffer buf = ByteBuffer.allocate(8 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
			buf.putInt(op).putInt(payload.length).put(payload).flip();
			if (socket != null) {
				while (buf.hasRemaining()) socket.write(buf);
			} else {
				pipe.write(buf.array());
			}
		}

		private JsonObject read() throws IOException {
			ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
			readFully(header);
			header.flip();
			header.getInt();
			int len = header.getInt();
			if (len < 0 || len > 1 << 20) throw new IOException("bad frame");
			ByteBuffer body = ByteBuffer.allocate(len);
			readFully(body);
			return JsonParser.parseString(new String(body.array(), StandardCharsets.UTF_8)).getAsJsonObject();
		}

		private void readFully(ByteBuffer buf) throws IOException {
			if (socket != null) {
				while (buf.hasRemaining()) if (socket.read(buf) < 0) throw new IOException("closed");
			} else {
				byte[] tmp = new byte[buf.remaining()];
				pipe.readFully(tmp);
				buf.put(tmp);
			}
		}

		@Override
		public void close() {
			try {
				if (socket != null) socket.close();
				if (pipe != null) pipe.close();
			} catch (IOException ignored) {
				// already closed
			}
		}
	}
}
