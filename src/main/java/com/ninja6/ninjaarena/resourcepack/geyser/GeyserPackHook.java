package com.ninja6.ninjaarena.resourcepack.geyser;

import com.ninja6.ninjaarena.resourcepack.BedrockIntegration;
import com.ninja6.ninjaarena.resourcepack.BedrockPackSettings;
import com.ninja6.ninjaarena.resourcepack.BedrockSelections;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.plugin.Plugin;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.geysermc.geyser.api.event.bedrock.SessionLoadResourcePacksEvent;
import org.geysermc.geyser.api.pack.PackCodec;
import org.geysermc.geyser.api.pack.ResourcePack;
import org.geysermc.geyser.api.pack.option.ResourcePackOption;

/** Same-server Paper adapter. Only public Geyser API classes belong in this package. */
public final class GeyserPackHook implements BedrockIntegration, EventRegistrar {
    private final GeyserApi api;
    private final GeyserEnforcement enforcement;
    private BedrockSelections selections;
    private ResourcePack standard;
    private ResourcePack detailed;
    private volatile boolean closed;

    public GeyserPackHook(Plugin plugin, BedrockPackSettings settings) {
        this.api = GeyserApi.api();
        enforcement = new GeyserEnforcement(settings.enabled() && settings.geyserEnforcesPacks(),
                () -> GeyserConfigSource.find(plugin.getServer().getPluginManager()), plugin.getLogger()::warning);
        enforcement.evaluate();
        if (!settings.enabled()) return;
        try {
            standard = load(plugin, settings.standard(), BedrockPackSettings.STANDARD_ID);
            if (settings.detailed().isPresent()) {
                detailed = load(plugin, settings.detailed().get(), BedrockPackSettings.DETAILED_ID);
            }
            selections = new BedrockSelections(BedrockPackSettings.STANDARD_ID, BedrockPackSettings.DETAILED_ID,
                    detailed != null);
            api.eventBus().subscribe(this, SessionLoadResourcePacksEvent.class, this::negotiate);
            plugin.getLogger().info("Bedrock pack selection is active; client load outcomes are not observable."
                    + " Geyser's force-resource-packs and integrated pack control acceptance for the whole connection.");
        } catch (IOException | RuntimeException e) {
            selections = null;
            standard = null;
            detailed = null;
            api.eventBus().unregisterAll(this);
            plugin.getLogger().severe("Bedrock pack delivery is off: " + e.getMessage()
                    + ". Fix the pinned files and restart. Bedrock tools keep vanilla visuals.");
        }
    }

    private static ResourcePack load(Plugin plugin, BedrockPackSettings.Pack pin, UUID expected) throws IOException {
        ResourcePack pack = ResourcePack.create(PackCodec.path(pin.checkedFile(plugin.getDataFolder().toPath())));
        if (pack.manifest().formatVersion() != 2 || !expected.equals(pack.uuid())) {
            throw new IOException("Bedrock manifest format or quality UUID differs from the supported pack: " + pin.file());
        }
        return pack;
    }

    private void negotiate(SessionLoadResourcePacksEvent event) {
        if (closed || selections == null) return;
        selections.negotiate(event.connection(), event.connection().xuid(), new BedrockSelections.Negotiator() {
            @Override public void remove(UUID pack) { event.unregister(pack); }
            @Override public void offer(BedrockSelections.Quality quality) {
                event.register(quality == BedrockSelections.Quality.DETAILED ? detailed : standard,
                        new ResourcePackOption<?>[0]);
            }
        });
    }

    @Override public boolean isBedrock(UUID player) {
        return !closed && api.connectionByUuid(player) != null;
    }

    @Override public boolean customHeld(UUID player) {
        if (closed || !enforcement.allowed() || selections == null) return false;
        var connection = api.connectionByUuid(player);
        return connection != null && selections.selected(connection);
    }

    @Override public Optional<BedrockSelections> selections() {
        return closed ? Optional.empty() : Optional.ofNullable(selections);
    }

    @Override public void refreshEnforcement() {
        if (!closed) enforcement.evaluate();
    }

    @Override public void close() {
        closed = true;
        api.eventBus().unregisterAll(this);
        if (selections != null) selections.clear();
    }
}
