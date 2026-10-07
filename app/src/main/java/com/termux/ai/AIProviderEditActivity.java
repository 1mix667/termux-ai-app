package com.termux.ai;

import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.HashMap;
import java.util.Map;

/**
 * Create / edit a single AI provider.
 *
 * Built-in Claude/Gemini presets expose only the fields their legacy flows
 * actually use (name, credentials, model for Gemini, enabled). OpenAI-compatible
 * providers (built-in presets and custom ones) expose the full field set.
 * Built-ins can never be deleted; their id is fixed.
 */
public class AIProviderEditActivity extends AppCompatActivity {

    public static final String EXTRA_PROVIDER_ID = "provider_id";

    private AIProviderStore store;
    private AIProviderConfig editing; // null when creating
    private boolean isNew;

    private EditText nameInput;
    private EditText apiKeyInput;
    private TextView apiKeyLabel;
    private EditText baseUrlInput;
    private EditText endpointInput;
    private Spinner authSchemeSpinner;
    private EditText headersInput;
    private EditText modelInput;
    private TextView modelLabel;
    private EditText systemPromptInput;
    private EditText temperatureInput;
    private EditText maxTokensInput;
    private SwitchMaterial enabledSwitch;

    private View openaiSection;
    private View modelRow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_provider_edit);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        store = AIProviderStore.getInstance(this);

        String providerId = getIntent().getStringExtra(EXTRA_PROVIDER_ID);
        if (providerId != null) {
            editing = store.getProvider(providerId);
        }
        isNew = editing == null;

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(isNew ? "Add AI Provider"
                    : "Edit " + (editing != null ? editing.getName() : "Provider"));
        }

        bindViews();
        setupAuthSchemeSpinner();

        if (!isNew) {
            populate(editing);
        } else {
            // Sensible defaults for a new OpenAI-compatible provider.
            authSchemeSpinner.setSelection(0);
            temperatureInput.setText("0.7");
            maxTokensInput.setText("1024");
            endpointInput.setText("/v1/chat/completions");
        }

        MaterialButton saveButton = findViewById(R.id.btn_save_provider);
        saveButton.setOnClickListener(v -> save());
    }

    private void bindViews() {
        nameInput = findViewById(R.id.input_provider_name);
        apiKeyInput = findViewById(R.id.input_api_key);
        apiKeyLabel = findViewById(R.id.label_api_key);
        baseUrlInput = findViewById(R.id.input_base_url);
        endpointInput = findViewById(R.id.input_endpoint);
        authSchemeSpinner = findViewById(R.id.spinner_auth_scheme);
        headersInput = findViewById(R.id.input_custom_headers);
        modelInput = findViewById(R.id.input_model);
        modelLabel = findViewById(R.id.label_model);
        systemPromptInput = findViewById(R.id.input_system_prompt);
        temperatureInput = findViewById(R.id.input_temperature);
        maxTokensInput = findViewById(R.id.input_max_tokens);
        enabledSwitch = findViewById(R.id.switch_enabled);
        openaiSection = findViewById(R.id.openai_section);
        modelRow = findViewById(R.id.model_row);
    }

    private void setupAuthSchemeSpinner() {
        String[] labels = {
                "Bearer token (Authorization header)",
                "X-API-Key header",
                "Query parameter (?api_key=)",
                "None (local server, no auth)",
                "OAuth Bearer token"
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        authSchemeSpinner.setAdapter(adapter);
    }

    private void populate(AIProviderConfig config) {
        nameInput.setText(config.getName());
        String key = config.getApiKey();
        apiKeyInput.setText(key != null ? key : "");

        AIProviderConfig.ProviderKind kind = config.getKind();
        boolean openai = kind == AIProviderConfig.ProviderKind.OPENAI_COMPATIBLE;
        openaiSection.setVisibility(openai ? View.VISIBLE : View.GONE);

        boolean showModel = openai || kind == AIProviderConfig.ProviderKind.GEMINI;
        modelRow.setVisibility(showModel ? View.VISIBLE : View.GONE);

        if (kind == AIProviderConfig.ProviderKind.CLAUDE) {
            apiKeyLabel.setText("OAuth token");
            apiKeyInput.setHint("Paste Claude OAuth token");
        } else if (kind == AIProviderConfig.ProviderKind.GEMINI) {
            apiKeyLabel.setText("API key");
            apiKeyInput.setHint("Paste Gemini API key");
            modelLabel.setText("Model (informational)");
        }

        if (openai) {
            baseUrlInput.setText(config.getBaseUrl() != null ? config.getBaseUrl() : "");
            endpointInput.setText(config.getEndpointPath() != null ? config.getEndpointPath() : "");
            authSchemeSpinner.setSelection(authSchemeToIndex(config.getAuthScheme()));
            headersInput.setText(headersToText(config.getCustomHeaders()));
            systemPromptInput.setText(config.getSystemPrompt() != null ? config.getSystemPrompt() : "");
            temperatureInput.setText(String.valueOf(config.getTemperature()));
            maxTokensInput.setText(String.valueOf(config.getMaxTokens()));
        }
        if (showModel) {
            modelInput.setText(config.getModel() != null ? config.getModel() : "");
        }
        enabledSwitch.setChecked(config.isEnabled());
    }

    private void save() {
        String name = nameInput.getText().toString().trim();
        if (name.isEmpty()) {
            nameInput.setError("Name is required");
            return;
        }
        String apiKey = apiKeyInput.getText().toString().trim();

        AIProviderConfig config;
        AIProviderConfig.ProviderKind kind;
        if (isNew) {
            config = new AIProviderConfig();
            config.setId(AIProviderConfig.generateCustomId());
            config.setKind(AIProviderConfig.ProviderKind.OPENAI_COMPATIBLE);
            config.setBuiltin(false);
            kind = AIProviderConfig.ProviderKind.OPENAI_COMPATIBLE;
        } else {
            config = editing;
            kind = config.getKind();
        }
        config.setName(name);
        config.setEnabled(enabledSwitch.isChecked());

        if (kind == AIProviderConfig.ProviderKind.OPENAI_COMPATIBLE) {
            String baseUrl = baseUrlInput.getText().toString().trim();
            if (baseUrl.isEmpty()) {
                baseUrlInput.setError("Base URL is required");
                return;
            }
            // Be forgiving: prepend https:// when no scheme is given.
            if (!baseUrl.contains("://")) {
                baseUrl = "https://" + baseUrl;
            }
            config.setBaseUrl(baseUrl);
            config.setEndpointPath(endpointInput.getText().toString().trim());
            config.setAuthScheme(indexToAuthScheme(authSchemeSpinner.getSelectedItemPosition()));
            config.setCustomHeaders(parseHeaders(headersInput.getText().toString()));
            config.setModel(modelInput.getText().toString().trim());
            config.setSystemPrompt(systemPromptInput.getText().toString());
            config.setTemperature(parseFloat(temperatureInput.getText().toString(), 0.7f));
            config.setMaxTokens(parseInt(maxTokensInput.getText().toString(), 1024));
        } else if (kind == AIProviderConfig.ProviderKind.GEMINI) {
            config.setModel(modelInput.getText().toString().trim());
        }
        // Claude builtin: only name / token / enabled are editable.

        config.setApiKey(apiKey.isEmpty() ? null : apiKey);
        store.saveProvider(config);

        Toast.makeText(this, "Provider saved", Toast.LENGTH_SHORT).show();
        finish();
    }

    // ------------------------------------------------------------------ //
    // Helpers
    // ------------------------------------------------------------------ //

    private static int authSchemeToIndex(AIProviderConfig.AuthScheme scheme) {
        if (scheme == null) return 0;
        switch (scheme) {
            case X_API_KEY: return 1;
            case QUERY_PARAM: return 2;
            case NONE: return 3;
            case OAUTH_BEARER: return 4;
            case BEARER:
            default: return 0;
        }
    }

    private static AIProviderConfig.AuthScheme indexToAuthScheme(int index) {
        switch (index) {
            case 1: return AIProviderConfig.AuthScheme.X_API_KEY;
            case 2: return AIProviderConfig.AuthScheme.QUERY_PARAM;
            case 3: return AIProviderConfig.AuthScheme.NONE;
            case 4: return AIProviderConfig.AuthScheme.OAUTH_BEARER;
            case 0:
            default: return AIProviderConfig.AuthScheme.BEARER;
        }
    }

    private static String headersToText(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(e.getKey()).append(": ").append(e.getValue());
        }
        return sb.toString();
    }

    private static Map<String, String> parseHeaders(String text) {
        Map<String, String> headers = new HashMap<>();
        if (text == null) return headers;
        for (String line : text.split("\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (!name.isEmpty() && !value.isEmpty()) {
                headers.put(name, value);
            }
        }
        return headers;
    }

    private static float parseFloat(String text, float fallback) {
        try {
            return Float.parseFloat(text.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
