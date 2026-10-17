package dev.newcomerprotection;

import net.fabricmc.api.DedicatedServerModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NewcomerProtection implements DedicatedServerModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("newcomer-protection");

    @Override
    public void onInitializeServer() {
        LOGGER.info("Newcomer Protection project scaffold initialized.");
    }
}
