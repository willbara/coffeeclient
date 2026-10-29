package dev.coffeeclient.account;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * Microsoft account login without ever seeing your password: the device-code flow of the Minecraft
 * launcher's own client id (as MinecraftAuth, which uses, does it). You open a Microsoft page,
 * enter a short code and sign in there; Coffee keeps the refresh token and turns it into a Minecraft
 * session (Xbox Live, then XSTS, then the Minecraft services; the steps follow
 * MicrosoftLoginManager). Blocking: call from a worker thread.
 */
public final class MicrosoftAuth {
	private static final String CLIENT_ID = "00000000402b5328";
	private static final String SCOPE = "service::user.auth.xboxlive.com::MBI_SSL";
	private static final String DEVICE_CODE_URL = "https://login.live.com/oauth20_connect.srf";
	private static final String TOKEN_URL = "https://login.live.com/oauth20_token.srf";
	private static final String XBL_URL = "https://user.auth.xboxlive.com/user/authenticate";
	private static final String XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
	private static final String MC_LOGIN_URL = "https://api.minecraftservices.com/authentication/login_with_xbox";
	private static final String PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

	private MicrosoftAuth() {
	}

	/** A login failed; the message is meant for the user. */
	public static final class AuthException extends Exception {
		public AuthException(String message) {
			super(message);
		}
	}

	/** What to show the user: open {@link #link()} (or {@code uri}) and enter {@code userCode}. */
	public record DeviceCode(String userCode, String deviceCode, String uri, int interval, long expiresAt) {
		/** The page with the code already filled in. */
		public String link() {
			return uri + "?otc=" + URLEncoder.encode(userCode, StandardCharsets.UTF_8);
		}
	}

	/** A Minecraft session plus the refresh token to store for next time. */
	public record Login(String name, UUID uuid, String accessToken, String refreshToken) {
	}

	/** Profile of a Minecraft access token. */
	public record Profile(String name, UUID uuid) {
	}

	public static DeviceCode requestDeviceCode() throws AuthException {
		JsonObject json = postForm(DEVICE_CODE_URL, Map.of("client_id", CLIENT_ID, "scope", SCOPE, "response_type", "device_code"));
		if (!json.has("user_code")) throw new AuthException("Microsoft did not send a login code: " + error(json));
		int interval = json.has("interval") ? json.get("interval").getAsInt() : 5;
		long expiresIn = json.has("expires_in") ? json.get("expires_in").getAsLong() : 900;
		return new DeviceCode(json.get("user_code").getAsString(), json.get("device_code").getAsString(),
			json.get("verification_uri").getAsString(), interval, System.currentTimeMillis() + expiresIn * 1000);
	}

	/** Waits until the user has signed in on the Microsoft page, then logs in to Minecraft. */
	public static Login awaitDeviceCode(DeviceCode code, BooleanSupplier cancelled) throws AuthException {
		int interval = Math.max(1, code.interval());
		while (!cancelled.getAsBoolean()) {
			if (System.currentTimeMillis() > code.expiresAt()) throw new AuthException("The login code expired. Start again.");
			sleep(interval * 1000L);
			if (cancelled.getAsBoolean()) break;
			JsonObject json = postForm(TOKEN_URL, Map.of("client_id", CLIENT_ID, "device_code", code.deviceCode(),
				"grant_type", "urn:ietf:params:oauth:grant-type:device_code"));
			if (json.has("access_token")) return minecraft(json.get("access_token").getAsString(), json.get("refresh_token").getAsString());
			String error = json.has("error") ? json.get("error").getAsString() : "";
			switch (error) {
				case "authorization_pending" -> {
				}
				case "slow_down" -> interval += 5;
				case "authorization_declined", "access_denied" -> throw new AuthException("The login was declined.");
				case "expired_token" -> throw new AuthException("The login code expired. Start again.");
				default -> throw new AuthException("Microsoft login failed: " + error(json));
			}
		}
		throw new AuthException("Login cancelled.");
	}

	/** A fresh session from a stored refresh token (which Microsoft replaces with a new one). */
	public static Login refresh(String refreshToken) throws AuthException {
		JsonObject json = postForm(TOKEN_URL, Map.of("client_id", CLIENT_ID, "scope", SCOPE,
			"grant_type", "refresh_token", "refresh_token", refreshToken));
		if (!json.has("access_token")) throw new AuthException("Microsoft refused the saved login (sign in again): " + error(json));
		String refresh = json.has("refresh_token") ? json.get("refresh_token").getAsString() : refreshToken;
		return minecraft(json.get("access_token").getAsString(), refresh);
	}

	/** The name and id behind a Minecraft access token; fails when the token has expired. */
	public static Profile profile(String accessToken) throws AuthException {
		HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(PROFILE_URL))
			.header("Authorization", "Bearer " + accessToken).GET());
		if (response.statusCode() == 404) throw new AuthException("This account does not own Minecraft.");
		if (response.statusCode() == 401) throw new AuthException("The access token is invalid or has expired.");
		JsonObject json = parse(response);
		if (!json.has("id") || !json.has("name")) throw new AuthException("Could not read the Minecraft profile: " + error(json));
		return new Profile(json.get("name").getAsString(), uuid(json.get("id").getAsString()));
	}

	private static Login minecraft(String msAccessToken, String refreshToken) throws AuthException {
		// Xbox Live: try the ticket prefixes Microsoft has accepted for this client over the years.
		JsonObject xbl = null;
		for (String ticket : new String[] {"t=" + msAccessToken, "d=" + msAccessToken, msAccessToken}) {
			JsonObject properties = new JsonObject();
			properties.addProperty("AuthMethod", "RPS");
			properties.addProperty("SiteName", "user.auth.xboxlive.com");
			properties.addProperty("RpsTicket", ticket);
			JsonObject body = new JsonObject();
			body.add("Properties", properties);
			body.addProperty("RelyingParty", "http://auth.xboxlive.com");
			body.addProperty("TokenType", "JWT");
			HttpResponse<String> response = postJson(XBL_URL, body);
			if (response.statusCode() == 200) {
				xbl = parse(response);
				break;
			}
		}
		if (xbl == null || !xbl.has("Token")) throw new AuthException("Xbox Live refused the login.");
		String uhs = xbl.getAsJsonObject("DisplayClaims").getAsJsonArray("xui").get(0).getAsJsonObject().get("uhs").getAsString();

		JsonObject properties = new JsonObject();
		properties.addProperty("SandboxId", "RETAIL");
		JsonArray userTokens = new JsonArray();
		userTokens.add(xbl.get("Token").getAsString());
		properties.add("UserTokens", userTokens);
		JsonObject body = new JsonObject();
		body.add("Properties", properties);
		body.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
		body.addProperty("TokenType", "JWT");
		HttpResponse<String> xstsResponse = postJson(XSTS_URL, body);
		JsonObject xsts = parse(xstsResponse);
		if (xstsResponse.statusCode() != 200 || !xsts.has("Token")) throw new AuthException(xstsError(xsts));

		JsonObject login = new JsonObject();
		login.addProperty("identityToken", "XBL3.0 x=" + uhs + ";" + xsts.get("Token").getAsString());
		JsonObject mc = parse(postJson(MC_LOGIN_URL, login));
		if (!mc.has("access_token")) throw new AuthException("Minecraft services refused the login: " + error(mc));
		String accessToken = mc.get("access_token").getAsString();
		Profile profile = profile(accessToken);
		return new Login(profile.name(), profile.uuid(), accessToken, refreshToken);
	}

	private static String xstsError(JsonObject json) {
		long code = json.has("XErr") ? json.get("XErr").getAsLong() : 0;
		if (code == 2148916233L) return "This Microsoft account has no Xbox profile. Sign in once at minecraft.net first.";
		if (code == 2148916235L) return "Xbox Live is not available in your country.";
		if (code == 2148916236L || code == 2148916237L) return "This account needs adult verification on xbox.com.";
		if (code == 2148916238L) return "This is a child account: an adult must add it to a Microsoft family.";
		return "Xbox Live (XSTS) refused the login" + (code != 0 ? " (error " + code + ")" : "") + ".";
	}

	private static String error(JsonObject json) {
		for (String key : new String[] {"error_description", "errorMessage", "error", "message"}) {
			if (json.has(key) && json.get(key).isJsonPrimitive()) return json.get(key).getAsString();
		}
		return "no details";
	}

	private static UUID uuid(String id) {
		String dashed = id.length() == 32
			? id.substring(0, 8) + "-" + id.substring(8, 12) + "-" + id.substring(12, 16) + "-" + id.substring(16, 20) + "-" + id.substring(20)
			: id;
		return UUID.fromString(dashed);
	}

	private static JsonObject postForm(String url, Map<String, String> form) throws AuthException {
		String body = new LinkedHashMap<>(form).entrySet().stream()
			.map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
			.collect(Collectors.joining("&"));
		return parse(send(HttpRequest.newBuilder(URI.create(url))
			.header("Content-Type", "application/x-www-form-urlencoded")
			.POST(HttpRequest.BodyPublishers.ofString(body))));
	}

	private static HttpResponse<String> postJson(String url, JsonObject body) throws AuthException {
		return send(HttpRequest.newBuilder(URI.create(url))
			.header("Content-Type", "application/json")
			.header("Accept", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body.toString())));
	}

	private static HttpResponse<String> send(HttpRequest.Builder request) throws AuthException {
		try {
			return HTTP.send(request.timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
		} catch (IOException e) {
			throw new AuthException("Could not reach Microsoft: " + e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AuthException("Login cancelled.");
		}
	}

	private static JsonObject parse(HttpResponse<String> response) {
		try {
			JsonElement json = JsonParser.parseString(response.body());
			return json.isJsonObject() ? json.getAsJsonObject() : new JsonObject();
		} catch (RuntimeException e) {
			return new JsonObject();
		}
	}

	private static void sleep(long ms) throws AuthException {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AuthException("Login cancelled.");
		}
	}
}
