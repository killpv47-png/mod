package com.killpv47.stealthaddon;

import com.killpv47.stealthaddon.features.BaseSaver;
import com.killpv47.stealthaddon.features.FakePing;
import com.killpv47.stealthaddon.modules.ElytraHeadSlammer;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class StealthAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Stealth");

    @Override
    public void onInitialize() {
        LOG.info("[StealthAddon] Initializing...");

        // Always-active features (NOT modules)
        FakePing.init();
        BaseSaver.init();

        // Modules
        Modules.get().add(new ElytraHeadSlammer());

        LOG.info("[StealthAddon] Loaded successfully.");
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.killpv47.stealthaddon";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("killpv47-png", "mod");
    }
}
