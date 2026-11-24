package net.starryisle.starryonline;

import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

/** StarryOnline: hourly, daily and weekly online-time coin rewards. */
public final class StarryOnlinePlugin extends JavaPlugin {

    private OnlineService service;
    private PlayerListener listener;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        service = new OnlineService(this);
        listener = new PlayerListener(service);
        getServer().getPluginManager().registerEvents(listener, this);
        service.start();

        PluginCommand command = getCommand("mistonline");
        if (command != null) {
            StarryCommand executor = new StarryCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        } else {
            getLogger().severe("plugin.yml 缺少 mistonline 命令");
        }

        getLogger().info("MistOnline enabled.");
    }

    public void reloadService() {
        if (service != null) {
            service.stop();
        }
        if (listener != null) {
            HandlerList.unregisterAll(listener);
        }
        // Re-read config.yml from disk: getConfig() is cached, so rebuilding the
        // service alone would keep using the old tier values.
        reloadConfig();
        service = new OnlineService(this);
        listener = new PlayerListener(service);
        getServer().getPluginManager().registerEvents(listener, this);
        service.start();
    }

    public OnlineService service() {
        return service;
    }

    @Override
    public void onDisable() {
        if (service != null) {
            service.stop();
        }
    }
}
