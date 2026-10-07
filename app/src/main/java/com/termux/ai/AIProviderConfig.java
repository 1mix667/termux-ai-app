package com.termux.ai;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Configuration for a single AI provider.
 *
 * Plain POJO serialized to JSON via Gson for storage. The API key is
 * {@code transient} on purpose: it is NEVER written into the JSON blob and is
 * instead kept separately in encrypted SharedPreferences by {@link AIProviderStore}.
 */
public class AIProviderConfig {

    /**
     * How {@link AIClient} drives the provider.
     */
    public enum ProviderKind {
        /** Built-in Claude flow: OAuth on claude.ai/api with its custom endpoints. */
        CLAUDE,
        /** Built-in Gemini flow: generativelanguage REST API. */
        GEMINI,
        /** Any OpenAI-compatible {@code /v1/chat/completions} style endpoint. */
        OPENAI_COMPATIBLE
    }

    /**
     * Authorization scheme used for requests.
     */
    public enum AuthScheme {
        /** {@code Authorization: Bearer <key>} */
        BEARER,
        /** {@code x-api-key: <key>} header */
        X_API_KEY,
        /** {@code ?api_key=<key>} query parameter */
        QUERY_PARAM,
        /** {@code Authorization: Bearer <oauth token>} (Claude-style OAuth) */
        OAUTH_BEARER,
        /** No authorization (local servers such as Ollama / llama.cpp) */
        NONE
    }

    // Built-in provider ids. AIClient keeps its legacy "claude"/"gemini" flows
    // keyed on these exact ids for backwards compatibility.
    public static final String ID_CLAUDE = "claude";
    public static final String ID_GEMINI = "gemini";
    public static final String ID_OPENAI = "openai";
    public static final String ID_DEEPSEEK = "deepseek";
    public static final String ID_OPENROUTER = "openrouter";
    public static final String ID_GROQ = "groq";
    public static final String ID_OLLAMA = "ollama";
    public static final String ID_LLAMACPP = "llamacpp";

    private String id;
    private String name;
    private ProviderKind kind;
    private String baseUrl;
    private String endpointPath;
    private AuthScheme authScheme;
    private Map<String, String> customHeaders;
    private String model;
    private String systemPrompt;
    private float temperature;
    private int maxTokens;
    private boolean enabled;
    private boolean builtin;

    /** API key / token. Transient: never serialized into the stored JSON. */
    private transient String apiKey;

    /** Required for Gson. */
    public AIProviderConfig() {
        this.customHeaders = new HashMap<>();
        this.authScheme = AuthScheme.BEARER;
        this.kind = ProviderKind.OPENAI_COMPATIBLE;
        this.temperature = 0.7f;
        this.maxTokens = 1024;
        this.enabled = true;
        this.builtin = false;
    }

    public AIProviderConfig(@NonNull String id,
                            @NonNull String name,
                            @NonNull ProviderKind kind,
                            @Nullable String baseUrl,
                            @Nullable String endpointPath,
                            @NonNull AuthScheme authScheme,
                            @Nullable String model,
                            @Nullable String systemPrompt,
                            float temperature,
                            int maxTokens,
                            boolean enabled,
                            boolean builtin) {
        this();
        this.id = id;
        this.name = name;
        this.kind = kind;
        this.baseUrl = baseUrl;
        this.endpointPath = endpointPath;
        this.authScheme = authScheme;
        this.model = model;
        this.systemPrompt = systemPrompt;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.enabled = enabled;
        this.builtin = builtin;
    }

    // ------------------------------------------------------------------ //
    // Getters / setters
    // ------------------------------------------------------------------ //

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public ProviderKind getKind() { return kind; }
    public void setKind(ProviderKind kind) { this.kind = kind; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getEndpointPath() { return endpointPath; }
    public void setEndpointPath(String endpointPath) { this.endpointPath = endpointPath; }

    public AuthScheme getAuthScheme() { return authScheme; }
    public void setAuthScheme(AuthScheme authScheme) { this.authScheme = authScheme; }

    @NonNull
    public Map<String, String> getCustomHeaders() {
        if (customHeaders == null) customHeaders = new HashMap<>();
        return customHeaders;
    }
    public void setCustomHeaders(@Nullable Map<String, String> customHeaders) {
        this.customHeaders = customHeaders != null ? customHeaders : new HashMap<>();
    }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    public float getTemperature() { return temperature; }
    public void setTemperature(float temperature) { this.temperature = temperature; }

    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isBuiltin() { return builtin; }
    public void setBuiltin(boolean builtin) { this.builtin = builtin; }

    /** API key held in memory only; persisted separately by {@link AIProviderStore}. */
    @Nullable
    public String getApiKey() { return apiKey; }
    public void setApiKey(@Nullable String apiKey) { this.apiKey = apiKey; }

    public boolean isOpenAICompatible() { return kind == ProviderKind.OPENAI_COMPATIBLE; }

    /**
     * Generate a unique id for a user-created provider.
     */
    @NonNull
    public static String generateCustomId() {
        return "custom_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @NonNull
    @Override
    public String toString() {
        return "AIProviderConfig{id='" + id + "', name='" + name + "', kind=" + kind + "}";
    }
}
