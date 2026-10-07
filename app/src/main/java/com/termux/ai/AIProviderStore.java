package com.termux.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Storage for {@link AIProviderConfig}s.
 *
 * Everything is kept in encrypted SharedPreferences via
 * {@link EncryptedPreferencesManager}:
 * <ul>
 *   <li>provider list as a JSON array (API keys excluded — {@code transient})</li>
 *   <li>active provider id (reuses AIClient's legacy {@code "ai_provider"} key so
 *       existing "claude"/"gemini" selections keep working)</li>
 *   <li>per-provider API keys under {@code "provider_api_key_<id>"}; the legacy
 *       Claude OAuth token ({@code "auth_token"}) and Gemini key
 *       ({@code "gemini_api_key"}) locations are preserved for the built-ins</li>
 * </ul>
 */
public final class AIProviderStore {

    private static final String TAG = "AIProviderStore";

    /** Shared with AIClient's legacy preference file. */
    private static final String PREFS_NAME = "termux_ai_prefs";
    private static final String KEY_PROVIDER_LIST = "provider_list_v1";
    /** Legacy key also read/written by AIClient and the old settings screen. */
    private static final String KEY_ACTIVE_PROVIDER = "ai_provider";
    private static final String KEY_API_KEY_PREFIX = "provider_api_key_";

    // Legacy key names, kept in sync with AIClient.
    private static final String LEGACY_AUTH_TOKEN = "auth_token";
    private static final String LEGACY_GEMINI_KEY = "gemini_api_key";

    private static final String DEFAULT_SYSTEM_PROMPT =
            "You are an AI assistant integrated into the Termux terminal emulator. " +
            "You help users with shell commands, error diagnostics, and code generation. " +
            "Be concise, practical, and accurate. When asked for JSON, return ONLY valid JSON.";

    private static volatile AIProviderStore instance;

    private final SharedPreferences prefs;
    private final Gson gson;
    private final Type listType = new TypeToken<List<AIProviderConfig>>() {}.getType();

    private AIProviderStore(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        this.prefs = EncryptedPreferencesManager.getEncryptedPrefs(appContext, PREFS_NAME);
        this.gson = new Gson();
    }

    @NonNull
    public static AIProviderStore getInstance(@NonNull Context context) {
        AIProviderStore local = instance;
        if (local == null) {
            synchronized (AIProviderStore.class) {
                local = instance;
                if (local == null) {
                    instance = local = new AIProviderStore(context);
                }
            }
        }
        return local;
    }

    // ------------------------------------------------------------------ //
    // Provider list CRUD
    // ------------------------------------------------------------------ //

    /**
     * All providers: built-in presets first, then user-created ones.
     * API keys are resolved into each config (in memory only).
     */
    @NonNull
    public synchronized List<AIProviderConfig> getProviders() {
        List<AIProviderConfig> stored = readStoredList();
        List<AIProviderConfig> result = new ArrayList<>();

        // Always include every built-in preset; merge stored overrides on top.
        for (AIProviderConfig builtin : builtinPresets()) {
            AIProviderConfig override = findById(stored, builtin.getId());
            AIProviderConfig effective = override != null ? override : builtin;
            // Built-ins are never deletable: a stored copy may tweak fields but
            // the builtin flag is forced back on.
            effective.setBuiltin(true);
            result.add(effective);
        }
        for (AIProviderConfig config : stored) {
            if (config.getId() == null) continue;
            if (findById(result, config.getId()) == null) {
                config.setBuiltin(false);
                result.add(config);
            }
        }

        for (AIProviderConfig config : result) {
            config.setApiKey(getApiKey(config.getId()));
        }
        return result;
    }

    @Nullable
    public synchronized AIProviderConfig getProvider(@Nullable String id) {
        if (id == null) return null;
        for (AIProviderConfig config : getProviders()) {
            if (id.equals(config.getId())) return config;
        }
        return null;
    }

    /**
     * Insert a new provider or update the existing one with the same id.
     * The API key is stored separately (encrypted); the JSON blob never
     * contains it.
     */
    public synchronized void saveProvider(@NonNull AIProviderConfig config) {
        if (config.getId() == null || config.getId().isEmpty()) {
            throw new IllegalArgumentException("Provider id must not be empty");
        }
        List<AIProviderConfig> stored = readStoredList();
        boolean replaced = false;
        for (int i = 0; i < stored.size(); i++) {
            if (config.getId().equals(stored.get(i).getId())) {
                stored.set(i, config);
                replaced = true;
                break;
            }
        }
        if (!replaced) stored.add(config);
        writeStoredList(stored);
        // Persist the key alongside (may be null/empty to clear it).
        setApiKey(config.getId(), config.getApiKey());
    }

    /**
     * Delete a user-created provider. Built-ins and the active provider
     * cannot be deleted.
     *
     * @return true if deleted, false if not allowed / not found
     */
    public synchronized boolean deleteProvider(@Nullable String id) {
        if (id == null) return false;
        AIProviderConfig existing = getProvider(id);
        if (existing == null || existing.isBuiltin()) return false;
        if (id.equals(getActiveProviderId())) return false;

        List<AIProviderConfig> stored = readStoredList();
        boolean removed = false;
        Iterator<AIProviderConfig> it = stored.iterator();
        while (it.hasNext()) {
            if (id.equals(it.next().getId())) {
                it.remove();
                removed = true;
            }
        }
        if (removed) {
            writeStoredList(stored);
            prefs.edit().remove(KEY_API_KEY_PREFIX + id).apply();
        }
        return removed;
    }

    public synchronized void setProviderEnabled(@Nullable String id, boolean enabled) {
        AIProviderConfig config = getProvider(id);
        if (config == null) return;
        config.setEnabled(enabled);
        saveProvider(config);
    }

    // ------------------------------------------------------------------ //
    // Active provider
    // ------------------------------------------------------------------ //

    @NonNull
    public synchronized String getActiveProviderId() {
        String id = prefs.getString(KEY_ACTIVE_PROVIDER, AIProviderConfig.ID_CLAUDE);
        if (id == null || getProvider(id) == null) {
            id = AIProviderConfig.ID_CLAUDE;
        }
        return id;
    }

    /**
     * Never returns null: falls back to the Claude preset.
     */
    @NonNull
    public synchronized AIProviderConfig getActiveProvider() {
        AIProviderConfig config = getProvider(getActiveProviderId());
        if (config == null) {
            config = builtinClaude();
            config.setApiKey(getApiKey(config.getId()));
        }
        return config;
    }

    public synchronized void setActiveProviderId(@NonNull String id) {
        if (getProvider(id) == null) {
            throw new IllegalArgumentException("Unknown provider id: " + id);
        }
        prefs.edit().putString(KEY_ACTIVE_PROVIDER, id).apply();
    }

    /**
     * Reset provider list and active selection to factory defaults.
     * Stored API keys of custom providers are removed; built-in legacy
     * credential slots (Claude token, Gemini key) are left untouched.
     */
    public synchronized void resetToDefaults() {
        List<AIProviderConfig> stored = readStoredList();
        SharedPreferences.Editor editor = prefs.edit();
        editor.remove(KEY_PROVIDER_LIST);
        editor.remove(KEY_ACTIVE_PROVIDER);
        for (AIProviderConfig config : stored) {
            if (config.getId() != null && !isBuiltinId(config.getId())) {
                editor.remove(KEY_API_KEY_PREFIX + config.getId());
            }
        }
        editor.apply();
        Log.i(TAG, "AI providers reset to defaults");
    }

    // ------------------------------------------------------------------ //
    // API keys (encrypted storage only)
    // ------------------------------------------------------------------ //

    @Nullable
    public synchronized String getApiKey(@Nullable String providerId) {
        if (providerId == null) return null;
        if (AIProviderConfig.ID_CLAUDE.equals(providerId)) {
            return prefs.getString(LEGACY_AUTH_TOKEN, null);
        }
        if (AIProviderConfig.ID_GEMINI.equals(providerId)) {
            return prefs.getString(LEGACY_GEMINI_KEY, null);
        }
        return prefs.getString(KEY_API_KEY_PREFIX + providerId, null);
    }

    public synchronized void setApiKey(@Nullable String providerId, @Nullable String apiKey) {
        if (providerId == null) return;
        SharedPreferences.Editor editor = prefs.edit();
        if (AIProviderConfig.ID_CLAUDE.equals(providerId)) {
            if (apiKey == null) editor.remove(LEGACY_AUTH_TOKEN);
            else editor.putString(LEGACY_AUTH_TOKEN, apiKey);
        } else if (AIProviderConfig.ID_GEMINI.equals(providerId)) {
            if (apiKey == null) editor.remove(LEGACY_GEMINI_KEY);
            else editor.putString(LEGACY_GEMINI_KEY, apiKey);
        } else {
            if (apiKey == null) editor.remove(KEY_API_KEY_PREFIX + providerId);
            else editor.putString(KEY_API_KEY_PREFIX + providerId, apiKey);
        }
        editor.apply();
    }

    // ------------------------------------------------------------------ //
    // Built-in presets
    // ------------------------------------------------------------------ //

    @NonNull
    public static List<AIProviderConfig> builtinPresets() {
        List<AIProviderConfig> presets = new ArrayList<>();
        presets.add(builtinClaude());
        presets.add(builtinGemini());

        presets.add(new AIProviderConfig(
                AIProviderConfig.ID_OPENAI, "OpenAI",
                AIProviderConfig.ProviderKind.OPENAI_COMPATIBLE,
                "https://api.openai.com", "/v1/chat/completions",
                AIProviderConfig.AuthScheme.BEARER,
                "gpt-4o-mini", DEFAULT_SYSTEM_PROMPT, 0.7f, 1024, true, true));

        presets.add(new AIProviderConfig(
                AIProviderConfig.ID_DEEPSEEK, "DeepSeek",
                AIProviderConfig.ProviderKind.OPENAI_COMPATIBLE,
                "https://api.deepseek.com", "/v1/chat/completions",
                AIProviderConfig.AuthScheme.BEARER,
                "deepseek-chat", DEFAULT_SYSTEM_PROMPT, 0.7f, 1024, true, true));

        presets.add(new AIProviderConfig(
                AIProviderConfig.ID_OPENROUTER, "OpenRouter",
                AIProviderConfig.ProviderKind.OPENAI_COMPATIBLE,
                "https://openrouter.ai", "/api/v1/chat/completions",
                AIProviderConfig.AuthScheme.BEARER,
                "openai/gpt-4o-mini", DEFAULT_SYSTEM_PROMPT, 0.7f, 1024, true, true));

        presets.add(new AIProviderConfig(
                AIProviderConfig.ID_GROQ, "Groq",
                AIProviderConfig.ProviderKind.OPENAI_COMPATIBLE,
                "https://api.groq.com", "/openai/v1/chat/completions",
                AIProviderConfig.AuthScheme.BEARER,
                "llama-3.3-70b-versatile", DEFAULT_SYSTEM_PROMPT, 0.7f, 1024, true, true));

        presets.add(new AIProviderConfig(
                AIProviderConfig.ID_OLLAMA, "Ollama (local)",
                AIProviderConfig.ProviderKind.OPENAI_COMPATIBLE,
                "http://localhost:11434", "/v1/chat/completions",
                AIProviderConfig.AuthScheme.NONE,
                "llama3.1", DEFAULT_SYSTEM_PROMPT, 0.7f, 1024, true, true));

        presets.add(new AIProviderConfig(
                AIProviderConfig.ID_LLAMACPP, "llama.cpp (local)",
                AIProviderConfig.ProviderKind.OPENAI_COMPATIBLE,
                "http://localhost:8080", "/v1/chat/completions",
                AIProviderConfig.AuthScheme.NONE,
                "default", DEFAULT_SYSTEM_PROMPT, 0.7f, 1024, true, true));

        return presets;
    }

    @NonNull
    private static AIProviderConfig builtinClaude() {
        return new AIProviderConfig(
                AIProviderConfig.ID_CLAUDE, "Claude",
                AIProviderConfig.ProviderKind.CLAUDE,
                "https://claude.ai/api", "/analyze",
                AIProviderConfig.AuthScheme.OAUTH_BEARER,
                null, null, 0.7f, 1024, true, true);
    }

    @NonNull
    private static AIProviderConfig builtinGemini() {
        return new AIProviderConfig(
                AIProviderConfig.ID_GEMINI, "Gemini",
                AIProviderConfig.ProviderKind.GEMINI,
                "https://generativelanguage.googleapis.com",
                "/v1beta/models/gemini-2.0-flash:generateContent",
                AIProviderConfig.AuthScheme.X_API_KEY,
                "gemini-2.0-flash", null, 0.7f, 1024, true, true);
    }

    private static boolean isBuiltinId(@Nullable String id) {
        for (AIProviderConfig preset : builtinPresets()) {
            if (preset.getId().equals(id)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ //
    // Internal JSON persistence
    // ------------------------------------------------------------------ //

    @NonNull
    private List<AIProviderConfig> readStoredList() {
        String json = prefs.getString(KEY_PROVIDER_LIST, null);
        if (json == null || json.isEmpty()) return new ArrayList<>();
        try {
            List<AIProviderConfig> list = gson.fromJson(json, listType);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse stored provider list", e);
            return new ArrayList<>();
        }
    }

    private void writeStoredList(@NonNull List<AIProviderConfig> list) {
        // apiKey is transient -> never lands in this JSON.
        prefs.edit().putString(KEY_PROVIDER_LIST, gson.toJson(list)).apply();
    }

    @Nullable
    private static AIProviderConfig findById(@NonNull List<AIProviderConfig> list,
                                             @Nullable String id) {
        if (id == null) return null;
        for (AIProviderConfig config : list) {
            if (id.equals(config.getId())) return config;
        }
        return null;
    }
}
