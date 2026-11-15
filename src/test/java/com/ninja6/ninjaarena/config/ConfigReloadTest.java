package com.ninja6.ninjaarena.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ninja6.ninjaarena.kit.KitDefinition;
import com.ninja6.ninjaarena.kit.KitItems;
import com.ninja6.ninjaarena.match.MatchSettings;
import com.ninja6.ninjaarena.match.ScoringSettings;
import com.ninja6.ninjaarena.match.PointsSettings;
import com.ninja6.ninjaarena.message.Messages;
import com.ninja6.ninjaarena.portal.LobbyMode;
import com.ninja6.ninjaarena.portal.PlayerPortalSettings;
import com.ninja6.ninjaarena.resourcepack.BedrockPackSettings;
import com.ninja6.ninjaarena.resourcepack.ResourcePackSettings;
import com.ninja6.ninjaarena.resourcepack.geyser.GeyserConfigSource;
import com.ninja6.ninjaarena.resourcepack.geyser.GeyserEnforcement;
import com.ninja6.ninjaarena.tool.NinjaTools;
import com.ninja6.ninjaarena.spectate.SpectatorSettings;
import com.ninja6.ninjaarena.tool.ToolSettings;
import com.ninja6.ninjaarena.waiting.WaitingRoomSettings;
import com.ninja6.ninjaarena.world.ArenaWorldSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigReloadTest {
    @TempDir Path dir;
    private static final String EMPTY_KIT = "format: 1\nitems: []\n";
    private final KitItems items = new KitItems();
    private final Runtime target = new Runtime();
    private ConfigReload reload;

    private static class Runtime implements ConfigReload.Target {
        final List<String> calls = new ArrayList<>();
        boolean empty = true;
        boolean failWorld;
        ArenaWorldSettings world = ArenaWorldSettings.DEFAULTS;
        int cellSize = 256;
        WaitingRoomSettings waiting;
        MatchSettings match;
        boolean friendlyFire;
        ScoringSettings scoring;
        PointsSettings points;
        ToolSettings tools;
        SpectatorSettings spectating;
        PlayerPortalSettings playerPortals;
        KitDefinition kit;
        com.ninja6.ninjaarena.shop.ShopCatalogue shop;
        @Override public void shop(com.ninja6.ninjaarena.shop.ShopCatalogue catalogue) { shop = catalogue; }
        ConfigurationSection published;
        String season;
        Runnable enforcement = () -> { };
        Integer maxActive;
        final List<String> logged = new ArrayList<>();
        @Override public boolean canResize() { return empty; }
        @Override public void arenas(int maxActive) { calls.add("arenas"); this.maxActive = maxActive; }
        @Override public void warning(String message) { logged.add(message); }
        @Override public void world(ArenaWorldSettings settings) {
            calls.add("world"); world = new ArenaWorldSettings(world.borderSize(), settings.viewDistance(), settings.simulationDistance());
            if (failWorld) { failWorld = false; throw new IllegalStateException("simulation setter failed"); }
        }
        @Override public void grid(int cellSize, double borderSize) {
            calls.add("grid"); this.cellSize = cellSize;
            world = new ArenaWorldSettings(borderSize, world.viewDistance(), world.simulationDistance());
        }
        @Override public void waiting(WaitingRoomSettings settings) { calls.add("waiting"); waiting = settings; }
        @Override public void match(MatchSettings settings, boolean friendlyFire, ScoringSettings scoring) {
            calls.add("match"); match = settings; this.friendlyFire = friendlyFire; this.scoring = scoring;
        }
        @Override public void points(PointsSettings settings) { calls.add("points"); points = settings; }
        Integer rejoinGrace;
        @Override public void rejoinGrace(int seconds) { calls.add("rejoinGrace"); rejoinGrace = seconds; }
        com.ninja6.ninjaarena.match.ShopSettings shopPhase;
        @Override public void shopPhase(com.ninja6.ninjaarena.match.ShopSettings settings) {
            calls.add("shopPhase");
            shopPhase = settings;
        }
        @Override public void tools(ToolSettings settings) { calls.add("tools"); tools = settings; }
        @Override public void spectating(SpectatorSettings settings) { spectating = settings; }
        @Override public void playerPortals(PlayerPortalSettings settings) {
            calls.add("player-portals"); playerPortals = settings;
        }
        @Override public void kit(KitDefinition kit) { calls.add("kit"); this.kit = kit; }
        @Override public void season(String name) { calls.add("season"); season = name; }
        @Override public boolean eventArenaExists(String arena) { return arena.equals("dojo"); }
        @Override public void configuration(ConfigurationSection settings) { published = settings; }
        @Override public void bedrockEnforcement() { enforcement.run(); }
        com.ninja6.ninjaarena.map.SeedSettings maps;
        @Override public void maps(com.ninja6.ninjaarena.map.SeedSettings settings) { calls.add("maps"); maps = settings; }
    }

    @BeforeEach
    void setup() throws Exception {
        NinjaTools.registerItems(items);
        write("", EMPTY_KIT);
        reload = new ConfigReload(dir, ReloadFiles.defaults(), KitDefinition.EMPTY, items, target);
    }

    private void write(String config, String kit) throws Exception {
        Files.writeString(dir.resolve("config.yml"), config);
        Files.writeString(dir.resolve("kits.yml"), kit);
    }

    private ReloadFiles.Candidate candidate() throws Exception {
        return ReloadFiles.read(dir.resolve("config.yml"), dir.resolve("kits.yml"), items);
    }

    @Test
    void seedModesApplyAtEachSlotsNextGenerationOnReload() throws Exception {
        write("""
                maps:
                  default:
                    mode: rotation
                    seeds: [3, 4]
                    rotate-every: 2h
                  slots:
                    2: {mode: static, seed: 9}
                  seed-sharing: distinct
                """, EMPTY_KIT);
        List<String> report = reload.reload();
        assertEquals(List.of("The Arena reload complete.", "Changed: none.", "Postponed: maps (each slot's next map).",
                "Refused: none."), report);
        assertEquals(List.of("maps"), target.calls);
        assertEquals(com.ninja6.ninjaarena.map.SeedSettings.Mode.STATIC, target.maps.forSlot(2).mode());
        assertEquals(java.time.Duration.ofHours(2), target.maps.forSlot(1).rotateEvery());

        target.calls.clear();
        assertEquals("Postponed: none.", reload.reload().get(2), "the same settings again change nothing");
        assertEquals(List.of(), target.calls);

        write("maps: {default: {mode: rotation, seeds: [3, 4], rotate-every: 2h}}", EMPTY_KIT);
        assertEquals("Postponed: maps (each slot's next map).", reload.reload().get(2), "a slot override removed");
        assertEquals(java.util.Map.of(), target.maps.slots());
    }

    @Test
    void invalidSeedModesAreRejectedWithTheirLineAndNothingChanges() throws Exception {
        write("arenas:\n  max-active: 3\nmaps:\n  slots:\n    2:\n      mode: shuffle\n", EMPTY_KIT);
        assertEquals(List.of("The Arena reload was rejected: config.yml: line 6: maps.slots.2.mode: expected static,"
                + " rotation or random. Nothing changed."), reload.reload());
        assertEquals(List.of(), target.calls);
        write("maps:\n  default:\n    rotate-every: 30\n", EMPTY_KIT);
        assertTrue(reload.reload().getFirst().contains("line 3: maps.default.rotate-every: expected a duration"));
        write("maps:\n  default:\n    mode: static\n", EMPTY_KIT);
        assertTrue(reload.reload().getFirst().contains("maps.default.mode: static mode needs a seed"));
        write("maps:\n  colour: red\n", EMPTY_KIT);
        assertTrue(reload.reload().getFirst().contains("maps.colour: unknown setting"));
        assertEquals(List.of(), target.calls);
        assertEquals(null, target.maps);
    }

    @Test
    void slotOverridesSetAtStartupAreNotReportedAsAChange() throws Exception {
        String yaml = "maps:\n  slots:\n    1:\n      difficulty: 4\n";
        write(yaml, EMPTY_KIT);
        YamlConfiguration started = YamlConfiguration.loadConfiguration(new java.io.StringReader(yaml));
        started.setDefaults(ReloadFiles.defaults());
        reload = new ConfigReload(dir, started, KitDefinition.EMPTY, items, target);
        assertEquals("Postponed: none.", reload.reload().get(2));
        assertEquals(List.of(), target.calls);
    }

    @Test
    void brokenShopKeepsPreviousCatalogueAndOtherSettingsUntilAValidReload() throws Exception {
        var original = com.ninja6.ninjaarena.shop.ShopCatalogue.EMPTY;
        reload.enableShop(original, java.util.logging.Logger.getAnonymousLogger());
        Files.writeString(dir.resolve("shop.yml"), "items: [");
        var rejected = reload.reload();
        assertTrue(rejected.getFirst().contains("shop.yml"));
        org.junit.jupiter.api.Assertions.assertNull(target.shop);
        Files.writeString(dir.resolve("shop.yml"), "format: 1\nitems:\n"
                + "  - {id: iron, price: 2, limit: 1, category: armour, gives: {tier: iron}}\n");
        assertTrue(reload.reload().get(2).contains("shop.yml (next match)"));
        var accepted = target.shop;
        assertEquals(1, accepted.items().size());
        Files.writeString(dir.resolve("shop.yml"), "format: 2\nitems: []");
        reload.reload();
        assertSame(accepted, target.shop);
    }

    @Test
    void personalPointsOnlyReloadIsPostponedAndClampsNegatives() throws Exception {
        write("points: {player-kill: 7, enemy-kill-per-cost: 3, round-survive: -2, round-win: 9}", EMPTY_KIT);
        var messages = reload.reload();
        assertEquals(new PointsSettings(7, 3, 0, 9), target.points);
        assertTrue(messages.get(2).contains("points.player-kill (next match)"));
        assertTrue(messages.get(2).contains("points.round-survive (next match)"));
        assertEquals(List.of("points"), target.calls);
    }

    @Test
    void successfulReloadRechecksGeyserEvenWhenNinjaArenaSettingsAreUnchanged() throws Exception {
        boolean[] enforced = {true};
        int[] reads = {0};
        List<String> warnings = new ArrayList<>();
        GeyserConfigSource source = new GeyserConfigSource() {
            @Override public Path file() { return dir.resolve("geyser-config.yml"); }
            @Override public java.io.InputStream open() {
                reads[0]++;
                return new java.io.ByteArrayInputStream(("gameplay: {force-resource-packs: " + enforced[0] + "}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        };
        var gate = new GeyserEnforcement(true, () -> source, warnings::add);
        gate.evaluate();
        target.enforcement = gate::evaluate;
        assertTrue(gate.allowed());
        enforced[0] = false;
        assertTrue(reload.reload().getFirst().contains("complete"));
        assertFalse(gate.allowed());
        enforced[0] = true;
        assertTrue(reload.reload().getFirst().contains("complete"));
        assertTrue(gate.allowed());
        assertEquals(3, reads[0]);
        assertEquals(1, warnings.size());
        write("resource-pack: {bedrock: {mappings-installed: 'true'}}", EMPTY_KIT);
        assertTrue(reload.reload().getFirst().contains("rejected"));
        assertEquals(3, reads[0], "invalid NinjaArena candidates must not change the enforcement gate");
        assertTrue(gate.allowed());
    }

    @Test
    void invalidEventRejectsAllChangesAndDoesNotExposeWebhook() throws Exception {
        String event = "{id: friday, day: FRIDAY, time: '19:30', arena: dojo, duration-minutes: 90}";
        String good = "events:\n  time-zone: Europe/Paris\n  announcement-lead-minutes: [30, 1]\n  schedule:\n    - ";
        write(good + event, EMPTY_KIT);
        assertTrue(reload.reload().getFirst().contains("complete"));
        var accepted = target.published;
        assertEquals(1, accepted.getMapList("events.schedule").size());
        target.calls.clear();
        for (String bad : List.of(event.replace("19:30", "25:00"), event.replace("FRIDAY", "FUNDAY"),
                event.replace("dojo", "unknown"), event.replace("90", "0"),
                event.replace("90}", "90, typo: true}"), event + "\n    - " + event,
                event.replace("90}", "90, bonus: {reward-id: missing}}"), "null",
                event + "\n  webhook-url: 'http://example.test/secret-token'")) {
            write(good + bad + "\nwaiting-room: {min-players: 3}\n", EMPTY_KIT);
            String message = reload.reload().getFirst();
            assertTrue(message.contains("rejected") && message.contains("events"), message);
            assertFalse(message.contains("secret-token"));
            assertEquals(accepted, target.published);
            assertTrue(target.calls.isEmpty());
        }
    }

    @Test
    void rewardListsReloadAndAnInvalidRuleKeepsTheLastConfiguration() throws Exception {
        String valid = """
                rewards:
                  milestones:
                    - {id: veteran, stat: wins, threshold: 5, title: Veteran}
                  placings:
                    - {id: champion, stat: wins, rank: 1, trophy: Cup}
                  vault: {enabled: true, amount-per-match: 2.5, daily-cap: 10}
                """;
        write(valid, EMPTY_KIT);
        assertTrue(reload.reload().getFirst().contains("complete"));
        ConfigurationSection accepted = target.published;
        assertEquals(1, accepted.getMapList("rewards.milestones").size());
        write(valid.replace("rank: 1", "rank: 0"), EMPTY_KIT);
        assertTrue(reload.reload().getFirst().contains("rejected"));
        assertEquals(accepted, target.published);
        write(valid.replace("2.5", "2.555"), EMPTY_KIT);
        assertTrue(reload.reload().getFirst().contains("rejected"));
        assertEquals(accepted, target.published);
    }

    @Test
    void shopPhaseSettingsApplyToTheNextMatchAndBadValuesAreRejected() throws Exception {
        write("shop: {enabled: false, max-seconds: 60, quick-start-seconds: 10, map-wait-limit-seconds: 0}\n",
                EMPTY_KIT);
        var messages = reload.reload();
        assertTrue(messages.getFirst().contains("complete"), messages.toString());
        assertEquals(new com.ninja6.ninjaarena.match.ShopSettings(false, 60, 10, 0), target.shopPhase);
        assertTrue(messages.get(2).contains("shop.enabled (next match)"));
        assertTrue(messages.get(2).contains("shop.map-wait-limit-seconds (next match)"));
        assertEquals(List.of("shopPhase"), target.calls);

        target.calls.clear();
        for (String bad : List.of("shop: {max-seconds: 0}", "shop: {quick-start-seconds: 3601}",
                "shop: {map-wait-limit-seconds: -1}", "shop: {enabled: yes please}", "shop: {max-seconds: 1.5}")) {
            write(bad + "\n", EMPTY_KIT);
            assertTrue(reload.reload().getFirst().contains("rejected"), bad);
        }
        assertEquals(List.of(), target.calls);
        assertEquals(new com.ninja6.ninjaarena.match.ShopSettings(false, 60, 10, 0), target.shopPhase);
    }

    @Test
    void theRejoinGraceAppliesAtOnceForNewDisconnectsAndBadValuesAreRejected() throws Exception {
        write("match: {rejoin-grace-seconds: 30}\n", EMPTY_KIT);
        var messages = reload.reload();
        assertTrue(messages.getFirst().contains("complete"), messages.toString());
        assertEquals(30, target.rejoinGrace);
        assertTrue(messages.get(1).contains("match.rejoin-grace-seconds"), messages.toString());
        assertFalse(messages.get(2).contains("match.rejoin-grace-seconds"), "not postponed to the next match");
        assertTrue(target.calls.contains("rejoinGrace"));

        target.calls.clear();
        for (String bad : List.of("match: {rejoin-grace-seconds: -1}", "match: {rejoin-grace-seconds: 601}",
                "match: {rejoin-grace-seconds: 2.5}")) {
            write(bad + "\n", EMPTY_KIT);
            assertTrue(reload.reload().getFirst().contains("rejected"), bad);
        }
        assertEquals(List.of(), target.calls);
        assertEquals(30, target.rejoinGrace);

        write("match: {rejoin-grace-seconds: 0}\n", EMPTY_KIT);
        reload.reload();
        assertEquals(0, target.rejoinGrace, "0 turns rejoining off");
    }

    @Test
    void disconnectScoringIsValidatedLikeTheOtherMatchSettings() throws Exception {
        for (String bad : List.of("match: {disconnect-kill-window-seconds: -1}",
                "match: {disconnect-kill-window-seconds: 3601}", "match: {disconnect-kill-window-seconds: 1.5}",
                "match: {disconnect-penalty-points: -1}", "match: {disconnect-penalty-points: 2.5}")) {
            write(bad + "\n", EMPTY_KIT);
            assertTrue(reload.reload().getFirst().contains("rejected"), bad);
        }
        assertEquals(List.of(), target.calls);

        write("match: {disconnect-kill-window-seconds: 20, disconnect-penalty-points: 0}\n", EMPTY_KIT);
        var messages = reload.reload();
        assertTrue(messages.getFirst().contains("complete"), messages.toString());
        assertTrue(messages.toString().contains("match.disconnect-kill-window-seconds"), messages.toString());
        assertEquals(20, target.match.disconnectKillWindowSeconds());
        assertEquals(0, target.match.disconnectPenaltyPoints());
    }

    @Test
    void invalidSpectatorLimitKeepsTheLastValidSettings() throws Exception {
        write("spectating: {enabled: false, max-per-match: 3}\n", EMPTY_KIT);
        assertTrue(reload.reload().getFirst().contains("complete"));
        assertEquals(new SpectatorSettings(false, 3), target.spectating);

        write("spectating: {enabled: true, max-per-match: 65}\n", EMPTY_KIT);
        assertTrue(reload.reload().getFirst().contains("spectating.max-per-match"));
        assertEquals(new SpectatorSettings(false, 3), target.spectating);
    }

    @Test
    void anOldWaitingRoomConfigStillReloadsWithAWarningPerDeprecatedKey() throws Exception {
        write("waiting-room:\n  lobby: shared\n  min-players: 3\n  max-players: 8\n  team-size: 2\n"
                + "  countdown-seconds: 30\n  radius: 16\n", EMPTY_KIT);
        var report = reload.reload();
        assertTrue(report.getFirst().contains("complete"), report.toString());
        assertEquals(new WaitingRoomSettings(3, 2, LobbyMode.SHARED), target.waiting);
        for (String path : WaitingRoomSettings.DEPRECATED) {
            assertTrue(report.stream().anyMatch(line -> line.contains(path + " is from the waiting rooms")), path);
            assertTrue(target.logged.contains(WaitingRoomSettings.warning(path)), path);
        }

        write("waiting-room:\n  lobby: everywhere\n", EMPTY_KIT);
        assertTrue(reload.reload().getFirst().contains("waiting-room.lobby: expected dedicated or shared"));
        write("waiting-room:\n  team-size: 65\n", EMPTY_KIT);
        assertTrue(reload.reload().getFirst().contains("waiting-room.team-size"));
        assertEquals(new WaitingRoomSettings(3, 2, LobbyMode.SHARED), target.waiting);

        write("waiting-room:\n  min-players: 3\n", EMPTY_KIT);
        report = reload.reload();
        assertTrue(report.stream().noneMatch(line -> line.contains("waiting rooms")), report.toString());
        assertEquals(new WaitingRoomSettings(3), target.waiting);
    }

    @Test
    void playerPortalSettingsReloadImmediatelyAndBadValuesAreRejected() throws Exception {
        write("player-portals:\n  worlds:\n    - survival\n    - survival_nether\n  frame-material: gilded_blackstone\n"
                + "  max-per-player: 3\n  min-distance: 64\n  color: '#FF8800'\n", EMPTY_KIT);
        var report = reload.reload();
        assertTrue(report.getFirst().contains("complete"), report.toString());
        assertTrue(report.get(1).contains("player-portals.worlds"), report.toString());
        assertEquals(new PlayerPortalSettings(java.util.Set.of("survival", "survival_nether"),
                org.bukkit.Material.GILDED_BLACKSTONE, 3, 64, 0xFF8800), target.playerPortals);
        assertEquals(List.of("survival", "survival_nether"), target.published.getStringList("player-portals.worlds"));

        target.calls.clear();
        assertTrue(reload.reload().getFirst().contains("complete"));
        assertFalse(target.calls.contains("player-portals"), "unchanged settings are not applied again");

        for (String bad : List.of("worlds: world", "worlds: [" + com.ninja6.ninjaarena.world.ArenaWorld.NAME + "]",
                "worlds: [{a: b}]", "frame-material: obsidian", "frame-material: water", "color: teal",
                "max-per-player: 1001", "min-distance: -1")) {
            String key = "player-portals." + bad.substring(0, bad.indexOf(':'));
            write("player-portals:\n  " + bad + "\n", EMPTY_KIT);
            String message = reload.reload().getFirst();
            assertTrue(message.contains("rejected") && message.contains(key), bad + ": " + message);
        }
        assertEquals(3, target.playerPortals.maxPerPlayer());
    }

    @Test
    void placeholderLifetimeReloadsAndInvalidValuesKeepTheLastAcceptedValue() throws Exception {
        write("placeholders: {cache-seconds: 12}\n", EMPTY_KIT);
        assertTrue(reload.reload().get(1).contains("placeholders.cache-seconds"));
        assertEquals(12, target.published.getInt("placeholders.cache-seconds"));

        for (String invalid : List.of("0", "301", "1.5", "'20'")) {
            write("placeholders: {cache-seconds: " + invalid + "}\n", EMPTY_KIT);
            assertTrue(reload.reload().getFirst().contains("placeholders.cache-seconds"), invalid);
            assertEquals(12, target.published.getInt("placeholders.cache-seconds"));
        }
    }

    @Test
    void bundledSchemaAndKitValidateWithoutCallingItemProviders() throws Exception {
        Files.writeString(dir.resolve("config.yml"), ReloadFiles.defaults().saveToString());
        Files.writeString(dir.resolve("kits.yml"), "format: 1\nitems:\n  - slot: 1\n    id: ninjaarena:shuriken\n    amount: 8\n");
        assertEquals(8, candidate().kit().items().getFirst().amount());
        assertTrue(reload.reload().get(2).contains("kits.yml (next round)"));
    }

    private static String packConfig() {
        return """
                resource-pack:
                  enabled: true
                  required: false
                  thrown-visuals: false
                  timeout-seconds: 45
                  url: https://example.test/standard-v1.zip
                  sha1: '0123456789012345678901234567890123456789'
                  id: 11111111-1111-4111-8111-111111111111
                  detailed:
                    url: https://example.test/detailed-v1.zip
                    sha1: 'abcdefabcdefabcdefabcdefabcdefabcdefabcd'
                    id: 22222222-2222-4222-8222-222222222222
                """;
    }

    @Test
    void completePackConfigurationValidatesButOnlyFlightToggleReloads() throws Exception {
        write(packConfig(), EMPTY_KIT);
        var config = candidate().config();
        assertTrue(config.getBoolean("resource-pack.enabled"));
        assertFalse(config.getBoolean("resource-pack.required"));
        assertEquals(45, config.getInt("resource-pack.timeout-seconds"));
        var report = reload.reload();
        assertTrue(report.get(1).contains("resource-pack.thrown-visuals"), report.toString());
        for (String key : List.of("enabled", "required", "timeout-seconds", "url", "sha1", "id",
                "detailed.url", "detailed.sha1", "detailed.id")) {
            assertTrue(report.get(3).contains("resource-pack." + key + " (restart required)"), key);
        }
        assertFalse(target.published.getBoolean("resource-pack.enabled"));
        assertFalse(target.published.getBoolean("resource-pack.thrown-visuals"));
        assertEquals("", target.published.getString("resource-pack.url"));
    }

    private static String bedrockConfig() {
        return packConfig() + "  bedrock:\n    mappings-installed: true\n"
                + "    standard:\n      file: standard.mcpack\n      sha256: '" + "a".repeat(64) + "'\n";
    }

    @Test
    void bedrockAssertionAndFilePinsValidateButRequireRestart() throws Exception {
        assertFalse(ReloadFiles.defaults().getBoolean("resource-pack.bedrock.mappings-installed"));
        write(bedrockConfig(), EMPTY_KIT);
        assertTrue(candidate().config().getBoolean("resource-pack.bedrock.mappings-installed"));
        var report = reload.reload();
        for (String key : List.of("mappings-installed", "standard.file", "standard.sha256")) {
            assertTrue(report.get(3).contains("resource-pack.bedrock." + key + " (restart required)"), report.toString());
        }
        assertFalse(target.published.getBoolean("resource-pack.bedrock.mappings-installed"));
        assertEquals("", target.published.getString("resource-pack.bedrock.standard.file"));
    }

    @Test
    void removedBedrockKeysNameTheirReplacementsAndChangeNothing() throws Exception {
        for (String key : List.of("enabled", "geyser-enforces-packs")) {
            for (boolean value : List.of(false, true)) {
                for (boolean pins : List.of(false, true)) {
                    for (boolean enabled : List.of(false, true)) {
                        var config = new YamlConfiguration();
                        config.loadFromString(packConfig());
                        config.set("resource-pack.enabled", enabled);
                        if (pins) {
                            config.set("resource-pack.bedrock.standard.file", "standard.mcpack");
                            config.set("resource-pack.bedrock.standard.sha256", "a".repeat(64));
                        }
                        var section = config.getConfigurationSection("resource-pack");
                        var javaSettings = ResourcePackSettings.from(section);
                        config.set("resource-pack.bedrock." + key, value);
                        write(config.saveToString(), EMPTY_KIT);
                        var startup = assertThrows(IllegalArgumentException.class,
                                () -> BedrockPackSettings.validateRemovedKeys(section));
                        assertEquals(startup.getMessage(), assertThrows(IllegalArgumentException.class,
                                () -> BedrockPackSettings.from(section)).getMessage());
                        var rejected = assertThrows(ReloadYaml.Invalid.class, this::candidate);
                        assertTrue(rejected.getMessage().endsWith(startup.getMessage()), rejected.getMessage());
                        assertTrue(startup.getMessage().contains(key.equals("enabled") ? "resource-pack.enabled"
                                : "resource-pack.required"), startup.getMessage());
                        var report = reload.reload();
                        assertTrue(report.getFirst().contains(startup.getMessage()), report.toString());
                        assertTrue(target.calls.isEmpty());
                        assertEquals(javaSettings, ResourcePackSettings.from(section));
                    }
                }
            }
        }
    }

    @Test
    void invalidBedrockCandidateRollsBackBothFilesAndAllServices() throws Exception {
        write("waiting-room: {min-players: 8}\n", EMPTY_KIT);
        reload.reload();
        var accepted = target.published;
        target.calls.clear();
        for (String bad : List.of(bedrockConfig().replace("mappings-installed: true", "mappings-installed: 'true'"),
                bedrockConfig().replace("standard.mcpack", "../standard.mcpack"),
                bedrockConfig().replace("a".repeat(64), "bad-hash"),
                bedrockConfig().replace("standard:", "unknown:"))) {
            write(bad + "waiting-room: {min-players: 12}\n", "format: 1\nitems:\n  - slot: 1\n    material: STICK\n");
            var report = reload.reload();
            assertTrue(report.getFirst().contains("rejected"), report.toString());
            assertSame(accepted, target.published);
            assertTrue(target.calls.isEmpty());
        }
    }

    @Test
    void invalidEnabledPackRejectsBothFilesAndPreservesTheLastSettings() throws Exception {
        write("resource-pack: {thrown-visuals: false}\n", EMPTY_KIT);
        assertTrue(reload.reload().getFirst().contains("complete"));
        var accepted = target.published;
        target.calls.clear();
        for (String bad : List.of("resource-pack: {enabled: true}",
                packConfig().replace("https://example.test/standard-v1.zip", "http://example.test/pack.zip"),
                packConfig().replace("0123456789012345678901234567890123456789", "bad-hash"),
                packConfig().replace("11111111-1111-4111-8111-111111111111", "bad-id"),
                packConfig().replace("timeout-seconds: 45", "timeout-seconds: 601"),
                packConfig().replace("timeout-seconds: 45", "timeout-seconds: 1.5"),
                packConfig().replace("required: false", "required: 'false'"),
                packConfig().replace("thrown-visuals: false", "thrown-visuals: 'true'"),
                packConfig().replace("https://example.test/detailed-v1.zip", ""),
                packConfig().replace("22222222-2222-4222-8222-222222222222", "11111111-1111-4111-8111-111111111111"))) {
            write(bad + "\nwaiting-room: {min-players: 10}\n", EMPTY_KIT);
            var report = reload.reload();
            assertTrue(report.getFirst().contains("rejected") && report.getFirst().contains("resource-pack"),
                    report.toString());
            assertEquals(accepted, target.published);
            assertTrue(target.calls.isEmpty());
        }
    }

    @Test
    void thrownToolLimitsAndCooldownsReloadWithStrictBounds() throws Exception {
        for (String tool : List.of("shuriken", "smoke-bomb")) {
            for (String key : List.of("cooldown-seconds", "uses-per-round")) {
                int max = key.equals("cooldown-seconds") ? 60 : 64;
                for (int value : List.of(-1, max + 1)) {
                    write("tools:\n  " + tool + ":\n    " + key + ": " + value + "\n", EMPTY_KIT);
                    assertThrows(ReloadYaml.Invalid.class, this::candidate);
                }
            }
        }
        write("tools: {shuriken: {cooldown-seconds: 60, uses-per-round: 64}, "
                + "smoke-bomb: {cooldown-seconds: 9, uses-per-round: 3}}", EMPTY_KIT);
        assertTrue(reload.reload().getFirst().contains("complete"));
        assertEquals(60, target.tools.shurikenCooldownSeconds());
        assertEquals(64, target.tools.shurikenUsesPerRound());
        assertEquals(9, target.tools.smokeCooldownSeconds());
        assertEquals(3, target.tools.smokeUsesPerRound());
        assertEquals(64, items.stackLimit("ninjaarena:shuriken"));
        assertEquals(64, items.stackLimit("ninjaarena:smoke-bomb"));
        assertEquals(1, items.stackLimit("ninjaarena:dash"));
    }

    @Test
    void everyConfigLeafHasStrictTypeValidation() throws Exception {
        var defaults = ReloadFiles.defaults();
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key) || key.startsWith("sounds.")) continue;
            var invalid = ReloadFiles.defaults();
            invalid.set(key, defaults.get(key) instanceof Boolean ? "false"
                    : defaults.get(key) instanceof String ? List.of("not", "text") : "bad-number");
            write(invalid.saveToString(), EMPTY_KIT);
            var failure = assertThrows(ReloadYaml.Invalid.class, this::candidate, key);
            assertTrue(failure.getMessage().contains(key), failure.getMessage());
        }
    }

    @Test
    void oneBadValueRejectsBothFilesBeforeAnyImmediateOrPostponedChange() throws Exception {
        String changed = "waiting-room:\n  min-players: 3\ntools:\n  shuriken:\n    damage: 7.0\narena-world:\n  view-distance: 9\n";
        write(changed, "format: 1\nitems:\n  - slot: 1\n    id: missing:item\n");
        assertTrue(reload.reload().getFirst().contains("kits.yml: line 4"));
        assertTrue(target.calls.isEmpty());
        assertEquals(null, target.published);
        write(changed, EMPTY_KIT);
        var report = reload.reload();
        assertEquals(List.of("world", "waiting", "tools"), target.calls);
        assertEquals(3, target.waiting.minPlayers());
        assertEquals(7.0, target.tools.shurikenDamage());
        assertEquals(9, target.world.viewDistance());
        assertTrue(report.get(1).contains("waiting-room.min-players"));
    }

    @Test
    void reloadReportsEveryTableGroupAndPublishesOnlyAcceptedValues() throws Exception {
        write("""
                waiting-room:
                  min-players: 4
                  team-size: 12
                match:
                  prep-seconds: 9
                  round-seconds: 60
                  overrun-limit-seconds: 0
                  round-end-seconds: 2
                  match-end-seconds: 3
                  rounds: 3
                  extra-rounds: 2
                  friendly-fire: true
                scoring:
                  round-win-points: 7
                  player-kill-points: 3
                tools:
                  dash:
                    strength: 1.5
                arena-world:
                  view-distance: 10
                  simulation-distance: 8
                """, "format: 1\nitems:\n  - slot: 1\n    id: ninjaarena:dash\n");
        var report = reload.reload();
        assertEquals(List.of("world", "waiting", "match", "tools", "kit"), target.calls);
        assertEquals(new WaitingRoomSettings(4, 12, LobbyMode.DEDICATED), target.waiting);
        assertEquals(new MatchSettings(9, 60, 0, 2, 3, 3, 2), target.match);
        assertTrue(target.friendlyFire);
        assertEquals(new ScoringSettings(7, 3), target.scoring);
        assertEquals(1.5, target.tools.dashStrength());
        assertEquals(10, target.world.viewDistance());
        assertEquals(8, target.world.simulationDistance());
        assertTrue(report.get(2).contains("match.friendly-fire (next match)"));
        assertTrue(report.get(2).contains("scoring.round-win-points (next match)"));
        assertTrue(report.get(2).contains("kits.yml (next round)"));
        assertEquals("Refused: none.", report.get(3));
        target.calls.clear();
        assertEquals(List.of("The Arena reload complete.", "Changed: none.", "Postponed: none.", "Refused: none."), reload.reload());
        assertTrue(target.calls.isEmpty());
    }

    @Test
    void gridAndBorderAreRefusedWhenOccupiedButDistancesApplyAndCanResizeWhenEmpty() throws Exception {
        target.empty = false;
        write("arena-grid:\n  cell-size: 512\narena-world:\n  border-size: 1024\n  view-distance: 12\n", EMPTY_KIT);
        var report = reload.reload();
        assertTrue(report.get(3).contains("arena-grid.cell-size"));
        assertTrue(report.get(3).contains("arena-world.border-size"));
        assertEquals(2048, target.world.borderSize());
        assertEquals(12, target.world.viewDistance());
        assertEquals(256, target.cellSize);
        assertEquals(256, target.published.getInt("arena-grid.cell-size"));
        target.calls.clear();
        assertTrue(reload.reload().get(3).contains("restart required"));
        assertTrue(target.calls.isEmpty());
        target.empty = true;
        report = reload.reload();
        assertEquals(List.of("grid"), target.calls);
        assertEquals(512, target.cellSize);
        assertEquals(1024, target.world.borderSize());
        assertEquals("Refused: none.", report.get(3));
    }

    @Test
    void invalidGridIsRejectedEvenWhenItWouldHaveBeenRefusedByPolicy() throws Exception {
        target.empty = false;
        write("arena-grid:\n  cell-size: 513\nwaiting-room:\n  min-players: 10\n", EMPTY_KIT);
        assertTrue(reload.reload().getFirst().contains("config.yml: line 2"));
        assertTrue(target.calls.isEmpty());
    }

    @Test
    void reloadCannotEnlargeTheHardArenaExtent() throws Exception {
        write("arena-world:\n  border-size: 4096\n", EMPTY_KIT);
        assertTrue(reload.reload().getFirst().contains("2048"));
        assertTrue(target.calls.isEmpty());
    }

    @Test
    void worldSetterFailureRollsBackBeforeOtherServicesChange() throws Exception {
        target.failWorld = true;
        write("arena-world:\n  view-distance: 12\nwaiting-room:\n  min-players: 10\n", EMPTY_KIT);
        assertTrue(reload.reload().getFirst().contains("Old settings were restored"));
        assertEquals(List.of("world", "world"), target.calls);
        assertEquals(ArenaWorldSettings.DEFAULTS, target.world);
        assertEquals(null, target.waiting);
        assertEquals(null, target.published);
        target.calls.clear();
        assertTrue(reload.reload().get(1).contains("arena-world.view-distance"));
        assertEquals(List.of("world", "waiting"), target.calls);
    }

    @Test
    void shadowStalkerSettingsAreValidatedAndNeedARestart() throws Exception {
        write("director:\n  shadow-stalker:\n    blink:\n      distance: 30.0\n", EMPTY_KIT);
        var failure = assertThrows(ReloadYaml.Invalid.class, this::candidate);
        assertTrue(failure.getMessage().contains("director.shadow-stalker.blink.distance"), failure.getMessage());
        assertTrue(failure.getMessage().contains("blink range must not be below blink distance"));
        write("director:\n  shadow-stalker:\n    blink:\n      distance: 30.0\n      range: 20.0\n", EMPTY_KIT);
        assertTrue(assertThrows(ReloadYaml.Invalid.class, this::candidate).getMessage()
                .contains("director.shadow-stalker.blink.range"));
        write("director:\n  shadow-stalker:\n    max-alive: 6\n", EMPTY_KIT);
        assertTrue(assertThrows(ReloadYaml.Invalid.class, this::candidate).getMessage().contains("must be from 0.0 to 5.0"));
        write("director:\n  shadow-stalker:\n    health: 80.0\n", EMPTY_KIT);
        assertTrue(reload.reload().get(3).contains("director.shadow-stalker.health (restart required)"));
        assertEquals(40.0, target.published.getDouble("director.shadow-stalker.health"));
    }

    @Test
    void maxActiveAppliesOnReloadAndIsValidated() throws Exception {
        write("arenas:\n  max-active: 2\n", EMPTY_KIT);
        var report = reload.reload();
        assertEquals(List.of("arenas"), target.calls);
        assertEquals(2, target.maxActive);
        assertEquals("Changed: arenas.max-active.", report.get(1));
        assertEquals(2, target.published.getInt("arenas.max-active"));

        target.calls.clear();
        assertEquals(4, reload.reload().size(), "no warning without the old name");
        assertEquals(List.of(), target.calls, "an unchanged value is not applied again");

        write("arenas:\n  max-active: 0\n", EMPTY_KIT);
        assertTrue(assertThrows(ReloadYaml.Invalid.class, this::candidate).getMessage()
                .contains("arenas.max-active: must be from 1.0 to 100.0"));
        write("arenas:\n  max-active: 2.5\n", EMPTY_KIT);
        assertTrue(assertThrows(ReloadYaml.Invalid.class, this::candidate).getMessage().contains("expected an integer"));
    }

    @Test
    void theOldNameOfMaxActiveStillWorksWithAWarning() throws Exception {
        write("stability:\n  max-concurrent-matches: 3\n", EMPTY_KIT);
        var report = reload.reload();
        assertEquals(3, target.maxActive);
        assertEquals("stability.max-concurrent-matches has been renamed arenas.max-active. It still works for now;"
                + " rename it in config.yml.", report.get(4));
        assertEquals(1, target.logged.size());
        assertTrue(target.logged.getFirst().contains("renamed arenas.max-active"), target.logged.toString());

        write("stability:\n  max-concurrent-matches: 101\n", EMPTY_KIT);
        assertTrue(assertThrows(ReloadYaml.Invalid.class, this::candidate).getMessage()
                .contains("stability.max-concurrent-matches: must be from 1.0 to 100.0"));

        // Set beside the new name, the old one is ignored.
        write("arenas:\n  max-active: 5\nstability:\n  max-concurrent-matches: 3\n", EMPTY_KIT);
        report = reload.reload();
        assertEquals(5, target.maxActive);
        assertTrue(report.get(4).contains("also set, so it is ignored"), report.toString());
    }

    @Test
    void theOldNameReadAtStartupIsTheValueAReloadComparesWith() throws Exception {
        var started = ReloadFiles.defaults();
        var withOld = new org.bukkit.configuration.file.YamlConfiguration();
        withOld.set("stability.max-concurrent-matches", 3);
        withOld.setDefaults(started);
        var fromOld = new ConfigReload(dir, withOld, KitDefinition.EMPTY, items, target);
        write("arenas:\n  max-active: 3\n", EMPTY_KIT);
        fromOld.reload();
        assertEquals(List.of(), target.calls, "3 was already in force");
    }

    @Test
    void settingsOutsideTheReloadTableRemainRestartOnly() throws Exception {
        write("director:\n  debug: true\nstability:\n  match-mob-cap: 5\n", EMPTY_KIT);
        assertTrue(reload.reload().get(3).contains("director.debug (restart required)"));
        assertTrue(reload.reload().get(3).contains("stability.match-mob-cap (restart required)"));
        assertTrue(target.calls.isEmpty());
        assertFalse(target.published.getBoolean("director.debug"));
    }

    @Test
    void hazardSettingsAreValidatedAndRestartOnly() throws Exception {
        for (String config : List.of("stability:\n  hazards:\n    max-lava-sources-per-map: 257\n",
                "stability:\n  hazards:\n    max-lava-sources-per-map: -1\n",
                "stability:\n  hazards:\n    max-lava-sources: 1025\n",
                "stability:\n  hazards:\n    max-lava-sources: 2.5\n",
                "stability:\n  hazards:\n    lava-flow-seconds: 0\n",
                "stability:\n  hazards:\n    lava-flow-seconds: 3601\n",
                "stability:\n  hazards:\n    unknown: 1\n")) {
            write(config, EMPTY_KIT);
            var failure = assertThrows(ReloadYaml.Invalid.class, this::candidate, config);
            assertTrue(failure.getMessage().contains("stability.hazards."), failure.getMessage());
        }
        write("stability:\n  hazards:\n    max-lava-sources-per-map: 64\n    lava-flow-seconds: 60\n", EMPTY_KIT);
        var report = reload.reload();
        assertTrue(report.get(3).contains("stability.hazards.max-lava-sources-per-map (restart required)"),
                report.get(3));
        assertTrue(report.get(3).contains("stability.hazards.lava-flow-seconds (restart required)"), report.get(3));
        assertTrue(target.calls.isEmpty());
    }

    @Test
    void placementSettingsAreValidatedAndRestartOnly() throws Exception {
        for (String config : List.of("stability:\n  placement:\n    budget-ms: 0.05\n",
                "stability:\n  placement:\n    budget-ms: 51\n",
                "stability:\n  placement:\n    slow-budget-ms: -1\n",
                "stability:\n  placement:\n    recovery-seconds: 601\n",
                "stability:\n  placement:\n    max-map-size: 31\n",
                "stability:\n  placement:\n    max-map-size: 300.5\n",
                "stability:\n  placement:\n    budget-ms: 2.0\n    slow-budget-ms: 3.0\n",
                "stability:\n  placement:\n    budget-ms: 0.5\n",
                "stability:\n  placement:\n    unknown: 1\n")) {
            write(config, EMPTY_KIT);
            var failure = assertThrows(ReloadYaml.Invalid.class, this::candidate, config);
            assertTrue(failure.getMessage().contains("stability.placement."), failure.getMessage());
        }
        write("stability:\n  placement:\n    budget-ms: 5.0\n    max-map-size: 128\n", EMPTY_KIT);
        var report = reload.reload();
        assertTrue(report.get(3).contains("stability.placement.budget-ms (restart required)"), report.get(3));
        assertTrue(report.get(3).contains("stability.placement.max-map-size (restart required)"), report.get(3));
        assertTrue(target.calls.isEmpty());
    }

    @Test
    void aNewSeasonAppliesOnReloadWhileStatsStorageStaysRestartOnly() throws Exception {
        write("stats:\n  season: Season 2\n  storage: mysql\n  mysql:\n    password: changed\n", EMPTY_KIT);
        var report = reload.reload();
        assertEquals(List.of("season"), target.calls);
        assertEquals("Season 2", target.season);
        assertEquals("Changed: stats.season.", report.get(1));
        assertTrue(report.get(3).contains("stats.storage (restart required)"), report.get(3));
        assertTrue(report.get(3).contains("stats.mysql.password (restart required)"), report.get(3));
        assertFalse(String.join(" ", report).contains("changed"), report.toString());
        assertEquals("sqlite", target.published.getString("stats.storage"));
        target.calls.clear();
        reload.reload();
        assertTrue(target.calls.isEmpty());
    }

    @Test
    void statsTextSettingsAreValidatedOnReload() throws Exception {
        for (String config : List.of("stats:\n  storage: postgres\n", "stats:\n  season: 2026\n",
                "stats:\n  season: ''\n", "stats:\n  table-prefix: Bad-Prefix\n",
                "stats:\n  sqlite:\n    file: ../stats.db\n", "stats:\n  mysql:\n    port: 70000\n")) {
            write(config, EMPTY_KIT);
            var failure = assertThrows(ReloadYaml.Invalid.class, this::candidate, config);
            assertTrue(failure.getMessage().contains("stats."), failure.getMessage());
        }
        write("stats:\n  season: 2026\n", EMPTY_KIT);
        assertTrue(reload.reload().getFirst().contains("config.yml: line 2: stats.season: expected text"));
        assertTrue(target.calls.isEmpty());
    }

    @Test
    void missingFilesAreRejectedWithoutFallbackAndArenaDataIsNeverReloaded() throws Exception {
        Files.delete(dir.resolve("kits.yml"));
        assertTrue(reload.reload().getFirst().contains("kits.yml: line 1: cannot read"));
        write("", EMPTY_KIT);
        Files.delete(dir.resolve("config.yml"));
        assertTrue(reload.reload().getFirst().contains("config.yml: line 1: cannot read"));
        assertTrue(target.calls.isEmpty());
        for (String name : List.of("arenas.yml", "portals.yml", "snapshot.dat")) Files.writeString(dir.resolve(name), "unchanged");
        write("waiting-room:\n  min-players: 8\n", EMPTY_KIT);
        reload.reload();
        for (String name : List.of("arenas.yml", "portals.yml", "snapshot.dat")) assertEquals("unchanged", Files.readString(dir.resolve(name)));
    }

    @Test
    void errorsNameActualLinesForUnknownDuplicateAndMissingFields() throws Exception {
        var badConfig = Map.of(
                "unknown:\n  child: 4\n", 1,
                "match:\n  rounds: 2\n  rounds: 3\n", 3,
                "# comment\nwaiting-room: 4\n", 2,
                "# comment\nstability:\n  pause-spawning-above-mspt: 20.0\n", 3,
                "# comment\n? [bad, key]\n: 5\n", 2);
        for (var entry : badConfig.entrySet()) {
            write(entry.getKey(), EMPTY_KIT);
            assertTrue(reload.reload().getFirst().contains("config.yml: line " + entry.getValue() + ":"), entry.toString());
        }
        write("", "format: 1\nitems:\n  - id: ninjaarena:dash\n");
        assertTrue(reload.reload().getFirst().contains("kits.yml: line 3: items.0.slot"));
    }

    @Test
    void nonMappingRootsReportTheirSourceLineWithoutChangingSettings() throws Exception {
        String invalidRoot = "# comment\n\n# another\n- invalid-root\n";
        write(invalidRoot, EMPTY_KIT);
        assertEquals("The Arena reload was rejected: config.yml: line 4: expected a mapping. Nothing changed.",
                reload.reload().getFirst());
        assertTrue(target.calls.isEmpty());

        write("", invalidRoot);
        assertEquals("The Arena reload was rejected: kits.yml: line 4: expected a mapping. Nothing changed.",
                reload.reload().getFirst());
        assertTrue(target.calls.isEmpty());
    }

    @Test
    void invalidKitsRejectUnknownKeysDuplicateSlotsNonItemsAndExcessiveAmounts() throws Exception {
        for (String entry : List.of("slot: 1\n    material: WATER", "slot: 1\n    material: WOODEN_SWORD\n    amount: 2",
                "slot: 1\n    id: ninjaarena:dash\n    amount: 2", "slot: 1\n    id: ninjaarena:dash\n    other: 1",
                "slot: 1\n    id: ninjaarena:dash\n  - slot: 1\n    id: ninjaarena:dash")) {
            write("", "format: 1\nitems:\n  - " + entry + "\n");
            assertThrows(ReloadYaml.Invalid.class, this::candidate, entry);
        }
        assertTrue(target.calls.isEmpty());
    }

    @Test
    void numericAndBooleanYamlTypesDoNotSilentlyClampOrCoerceStrings() throws Exception {
        write("match:\n  rounds: 0x14\n  friendly-fire: yes\n", EMPTY_KIT);
        assertEquals(20, candidate().config().getInt("match.rounds"));
        assertTrue(candidate().config().getBoolean("match.friendly-fire"));
        for (String config : List.of("match:\n  rounds: 2.0\n", "match:\n  rounds: 26\n",
                "match:\n  friendly-fire: 'false'\n", "tools:\n  shuriken:\n    damage: .nan\n",
                "tools:\n  shuriken:\n    damage: .inf\n", "match:\n  rounds: 999999999999999999999999\n")) {
            write(config, EMPTY_KIT);
            assertThrows(ReloadYaml.Invalid.class, this::candidate, config);
        }
    }

    @Test
    void declaredProviderLimitsValidateWithoutInvokingPlayerFactories() throws Exception {
        items.register("test:declared", 16, player -> { throw new AssertionError("must not create items on reload"); });
        items.register("test:legacy", player -> { throw new AssertionError("must not create items on reload"); });
        write("", "format: 1\nitems:\n  - slot: 1\n    id: test:declared\n    amount: 16\n");
        assertEquals(16, candidate().kit().items().getFirst().amount());
        write("", "format: 1\nitems:\n  - slot: 1\n    id: test:legacy\n    amount: 2\n");
        assertTrue(assertThrows(ReloadYaml.Invalid.class, this::candidate).getMessage().contains("stack limit of 1"));
        write("", "format: 1\nitems:\n  - slot: 1\n    material: WOODEN_SWORD\n");
        assertEquals(1, candidate().kit().items().getFirst().amount());
    }

    /** Startup and reload read messages.yml with one rule, so they accept and reject the same files. */
    @Test
    void reloadAcceptsAndRejectsTheSameMessagesFilesAsStartup() throws Exception {
        Path file = dir.resolve("messages.yml");
        try {
            for (String accepted : List.of("match:\n  fight: \"Go!\"\n", "match:\n  Fight: \"x\"\n",
                    "Match_Typo:\n  fight: 3\n", "")) {
                Files.writeString(file, accepted);
                List<String> startupWarnings = new ArrayList<>();
                Messages.read(file, startupWarnings::add);
                List<String> report = reload.reload();
                assertTrue(report.getFirst().contains("complete"), accepted + " -> " + report);
            }
            for (String rejected : List.of("match:\n  fight: [\n", "match: {fight: \"a\"\n", "\tmatch: x\n")) {
                Files.writeString(file, rejected);
                java.io.IOException startup = assertThrows(java.io.IOException.class,
                        () -> Messages.read(file, message -> { }));
                // One short line with the position, like the config.yml and kits.yml rejections.
                assertTrue(startup.getMessage().matches("line \\d+, column \\d+: [^\\n]+"), startup.getMessage());
                String report = reload.reload().getFirst();
                assertEquals("The Arena reload was rejected: messages.yml: " + startup.getMessage()
                        + ". Nothing changed.", report);
            }
        } finally {
            Messages.use(Messages.bundled());
        }
    }

    @Test
    void anUnknownMessageKeyIsIgnoredWithAWarningAtStartupAndOnReload() throws Exception {
        List<String> warnings = new ArrayList<>();
        ConfigReload withWarnings = new ConfigReload(dir, ReloadFiles.defaults(), KitDefinition.EMPTY, items,
                new Runtime() {
                    @Override public void warning(String message) { warnings.add(message); }
                });
        Path file = dir.resolve("messages.yml");
        Files.writeString(file, "match:\n  Fight: \"x\"\n  fight: \"Go!\"\n");
        try {
            List<String> startup = new ArrayList<>();
            Messages read = Messages.read(file, startup::add);
            assertEquals(List.of("messages.yml: match.Fight is not a message this version knows; it is ignored."),
                    startup);
            assertTrue(withWarnings.reload().getFirst().contains("complete"));
            assertTrue(warnings.contains(startup.getFirst()), warnings.toString());
            assertEquals(read, Messages.active());
        } finally {
            Messages.use(Messages.bundled());
        }
    }

    @Test
    void reloadPicksUpAChangedMessagesFileAndRejectsABrokenOne() throws Exception {
        List<String> warnings = new ArrayList<>();
        ConfigReload withWarnings = new ConfigReload(dir, ReloadFiles.defaults(), KitDefinition.EMPTY, items,
                new Runtime() {
                    @Override public void warning(String message) { warnings.add(message); }
                });
        try {
            Files.writeString(dir.resolve("messages.yml"), "match:\n  fight: \"<aqua>Go, ninjas!\"\n");
            var report = withWarnings.reload();
            assertTrue(report.getFirst().contains("complete"), report.toString());
            assertTrue(report.get(1).contains("messages.yml"), report.toString());
            assertEquals("Go, ninjas!", Messages.text("match.fight"));
            // Keys the server file leaves out keep their default, with one warning each.
            assertEquals("You left the match.", Messages.text("match.left"));
            assertEquals(1, warnings.stream()
                    .filter("messages.yml: match.left is missing; using the default."::equals).count());
            assertEquals("Changed: none.", withWarnings.reload().get(1));

            Files.writeString(dir.resolve("messages.yml"), "match:\n  fight: [\n");
            String rejected = withWarnings.reload().getFirst();
            assertTrue(rejected.startsWith("The Arena reload was rejected: messages.yml: "), rejected);
            assertEquals("Go, ninjas!", Messages.text("match.fight"));
        } finally {
            Messages.use(Messages.bundled());
        }
    }
}
