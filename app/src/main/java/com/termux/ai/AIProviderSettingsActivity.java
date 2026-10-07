package com.termux.ai;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.List;

/**
 * Lists all AI providers (built-in presets + user-defined).
 *
 * Users can pick the active provider, toggle providers on/off, add/edit/delete
 * custom providers, and reset everything to factory defaults.
 */
public class AIProviderSettingsActivity extends AppCompatActivity {

    private AIProviderStore store;
    private ProviderAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_providers);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("AI Providers");
        }

        store = AIProviderStore.getInstance(this);

        RecyclerView recyclerView = findViewById(R.id.provider_recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ProviderAdapter();
        recyclerView.setAdapter(adapter);

        MaterialButton addButton = findViewById(R.id.btn_add_provider);
        addButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, AIProviderEditActivity.class);
            startActivity(intent);
        });

        MaterialButton resetButton = findViewById(R.id.btn_reset_providers);
        resetButton.setOnClickListener(v -> confirmReset());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshList();
    }

    private void refreshList() {
        adapter.setProviders(store.getProviders(), store.getActiveProviderId());
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("Reset Providers?")
                .setMessage("This will remove all custom providers and restore the built-in " +
                        "presets and the default active provider. Stored API keys of custom " +
                        "providers will be deleted.")
                .setPositiveButton("Reset", (dialog, which) -> {
                    store.resetToDefaults();
                    refreshList();
                    Toast.makeText(this, "Providers reset to defaults", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDelete(AIProviderConfig provider) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Provider?")
                .setMessage("Delete \"" + provider.getName() + "\"? Its stored API key will be removed too.")
                .setPositiveButton("Delete", (dialog, which) -> {
                    if (store.deleteProvider(provider.getId())) {
                        refreshList();
                        Toast.makeText(this, "Provider deleted", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Cannot delete the active provider", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private class ProviderAdapter extends RecyclerView.Adapter<ProviderAdapter.ViewHolder> {

        private List<AIProviderConfig> providers = java.util.Collections.emptyList();
        private String activeId = "";

        void setProviders(List<AIProviderConfig> providers, String activeId) {
            this.providers = providers;
            this.activeId = activeId;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_ai_provider, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            AIProviderConfig provider = providers.get(position);
            boolean isActive = provider.getId().equals(activeId);

            holder.name.setText(provider.getName());
            holder.meta.setText(buildMeta(provider));
            holder.activeRadio.setChecked(isActive);
            // Avoid re-triggering the listener while binding.
            holder.enabledSwitch.setOnCheckedChangeListener(null);
            holder.enabledSwitch.setChecked(provider.isEnabled());

            holder.builtinTag.setVisibility(provider.isBuiltin() ? View.VISIBLE : View.GONE);
            holder.deleteButton.setVisibility(provider.isBuiltin() ? View.GONE : View.VISIBLE);

            holder.activeRadio.setOnClickListener(v -> {
                store.setActiveProviderId(provider.getId());
                refreshList();
                Toast.makeText(AIProviderSettingsActivity.this,
                        provider.getName() + " is now active", Toast.LENGTH_SHORT).show();
            });

            holder.enabledSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                store.setProviderEnabled(provider.getId(), isChecked);
                // Refresh meta/badges without losing scroll position.
                refreshList();
            });

            holder.editButton.setOnClickListener(v -> {
                Intent intent = new Intent(AIProviderSettingsActivity.this, AIProviderEditActivity.class);
                intent.putExtra(AIProviderEditActivity.EXTRA_PROVIDER_ID, provider.getId());
                startActivity(intent);
            });

            holder.deleteButton.setOnClickListener(v -> confirmDelete(provider));
        }

        private String buildMeta(AIProviderConfig provider) {
            StringBuilder sb = new StringBuilder();
            if (provider.getModel() != null && !provider.getModel().isEmpty()) {
                sb.append(provider.getModel());
            }
            if (provider.isOpenAICompatible()
                    && provider.getBaseUrl() != null && !provider.getBaseUrl().isEmpty()) {
                if (sb.length() > 0) sb.append(" • ");
                sb.append(provider.getBaseUrl());
            }
            if (!provider.isEnabled()) {
                if (sb.length() > 0) sb.append(" • ");
                sb.append("disabled");
            }
            String keyStatus = store.getApiKey(provider.getId());
            if (sb.length() > 0) sb.append(" • ");
            boolean needsKey = provider.getAuthScheme() != AIProviderConfig.AuthScheme.NONE;
            sb.append(needsKey
                    ? (keyStatus != null && !keyStatus.isEmpty() ? "key set" : "no key")
                    : "no auth");
            return sb.toString();
        }

        @Override
        public int getItemCount() {
            return providers.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            RadioButton activeRadio;
            TextView name;
            TextView meta;
            TextView builtinTag;
            SwitchMaterial enabledSwitch;
            ImageButton editButton;
            ImageButton deleteButton;

            ViewHolder(View itemView) {
                super(itemView);
                activeRadio = itemView.findViewById(R.id.provider_radio);
                name = itemView.findViewById(R.id.provider_name);
                meta = itemView.findViewById(R.id.provider_meta);
                builtinTag = itemView.findViewById(R.id.provider_builtin_tag);
                enabledSwitch = itemView.findViewById(R.id.provider_switch);
                editButton = itemView.findViewById(R.id.provider_edit);
                deleteButton = itemView.findViewById(R.id.provider_delete);
            }
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
