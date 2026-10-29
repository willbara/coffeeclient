package dev.coffeeclient.profile;

import com.google.gson.JsonObject;

/** A named snapshot of the client configuration. */
public final class Profile {
	private String name;
	private JsonObject config;
	private String notes = "";
	/** Server addresses this profile loads for on join, comma separated. */
	private String servers = "";

	public Profile(String name, JsonObject config) {
		this.name = name;
		this.config = config == null ? new JsonObject() : config;
	}

	public String name() {
		return name;
	}

	public void rename(String name) {
		if (name != null && !name.isBlank()) this.name = name.trim();
	}

	public JsonObject config() {
		return config;
	}

	public void setConfig(JsonObject config) {
		this.config = config == null ? new JsonObject() : config;
	}

	public String notes() {
		return notes;
	}

	public void setNotes(String notes) {
		this.notes = notes == null ? "" : notes;
	}

	public String servers() {
		return servers;
	}

	public void setServers(String servers) {
		this.servers = servers == null ? "" : servers.trim();
	}
}
