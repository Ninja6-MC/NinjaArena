package com.ninja6.ninjaarena.shop;

import com.ninja6.ninjaarena.match.MatchPhase;
import com.ninja6.ninjaarena.match.event.MatchPhaseChangeEvent;
import com.ninja6.ninjaarena.match.event.PlayerLeaveMatchEvent;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/** Invalidates screens when their player leaves or their shop phase ends; never opens one. */
public final class ShopListener implements Listener {
    private final ShopMenus menus;
    private final Function<UUID, Player> players;

    public ShopListener(ShopMenus menus, Function<UUID, Player> players) {
        this.menus = menus;
        this.players = players;
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) { menus.forget(event.getPlayer().getUniqueId()); }
    @EventHandler public void onLeave(PlayerLeaveMatchEvent event) { menus.close(event.player(), players); }
    @EventHandler public void onPhase(MatchPhaseChangeEvent event) {
        if (event.from() == MatchPhase.SHOP) {
            event.match().players().forEach(id -> menus.close(id, players));
        }
    }
}
