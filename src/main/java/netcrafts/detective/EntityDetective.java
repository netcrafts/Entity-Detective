package netcrafts.detective;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import netcrafts.detective.commands.LocateCommand;
import netcrafts.detective.query.EntityProfiler;

public class EntityDetective implements ModInitializer {
	public static final String MOD_ID = "entity-detective";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Entity Detective loaded.");
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> {
			LocateCommand.register(dispatcher);
		});
		// 5.5.7 — clean up per-player cooldown state on disconnect
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			LocateCommand.clearCooldown(handler.player.getUUID());
		});
		// Advance the entity profiler tick counter each server tick
		ServerTickEvents.END_SERVER_TICK.register(server -> EntityProfiler.INSTANCE.onServerTick(server));
	}
}