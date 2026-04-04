package netcrafts.detective;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import netcrafts.detective.commands.LocateCommand;

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
	}
}