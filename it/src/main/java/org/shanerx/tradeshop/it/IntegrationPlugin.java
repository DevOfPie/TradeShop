/*
 * Copyright (c) 2016-2026
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *                http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.shanerx.tradeshop.it;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * The in-server half of the tier-2 harness.
 *
 * <p>It runs once, from inside a real Paper server, after the server has finished
 * starting. Each scenario is a piece of behaviour a human would otherwise log in
 * to check; each writes one line to a result file that {@code ci/integration.sh}
 * reads after the process has exited. The plugin then shuts the server down
 * itself, so the runner never has to type {@code stop} into a stdin it has
 * deliberately closed.
 *
 * <h2>Why the result is a file and not a log line</h2>
 * The runner has to distinguish three states that all look alike from outside: a
 * run that passed, a run that failed, and a run that never happened. A log line
 * can be produced by a plugin that half-started; a file that declares how many
 * scenarios were registered and then lists which ones actually ran lets the
 * runner catch the third state, which is the one a green build hides.
 */
public final class IntegrationPlugin extends JavaPlugin implements Listener {

    /**
     * Deliberate sabotage, so the gate can be shown to fail rather than only
     * ever observed passing. Set by {@code ci/integration.sh} from
     * {@code TS_IT_INDUCE}. It lives in the harness permanently because a
     * failure mode you have to hand-edit into the code is one nobody re-checks.
     */
    private static final String INDUCE = System.getProperty("tradeshop.it.induce", "");

    private final List<Scenario> scenarios = new ArrayList<>();

    @Override
    public void onEnable() {
        register();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("integration harness armed with " + scenarios.size() + " scenario(s)"
                + (INDUCE.isEmpty() ? "" : ", INDUCED FAILURE MODE: " + INDUCE));
    }

    /**
     * The scenarios, in the order a person would perform them.
     */
    private void register() {
        scenarios.add(new Scenario("pluginEnabled", () -> {
            Plugin tradeShop = Bukkit.getPluginManager().getPlugin("TradeShop");
            Assert.that(tradeShop != null, "TradeShop is not installed on this server");
            Assert.that(tradeShop.isEnabled(),
                    "TradeShop is installed but not enabled - onEnable threw, and the server carried on");
        }));
    }

    /**
     * Scenarios run a tick after the server reports itself loaded.
     *
     * <p>{@link ServerLoadEvent} fires once the worlds and every plugin are up,
     * which is the earliest moment a scenario can touch a real block and get a
     * real answer.
     */
    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        getServer().getScheduler().runTaskLater(this, this::runEverything, 20L);
    }

    private void runEverything() {
        List<String> lines = new ArrayList<>();
        lines.add("SCENARIOS " + scenarios.size());

        for (int i = 0; i < scenarios.size(); i++) {
            Scenario scenario = scenarios.get(i);

            // "missing" is the failure this harness is least likely to notice on
            // its own: a scenario that silently does not run while the report
            // still says the suite finished.
            if ("missing".equals(INDUCE) && i == scenarios.size() - 1) {
                getLogger().info("INDUCED: skipping scenario " + scenario.name());
                continue;
            }

            lines.add("SCENARIO " + scenario.name() + " " + run(scenario));
        }

        if ("severe".equals(INDUCE)) {
            // Every assertion passed and the run is still not trustworthy. This
            // is the shape of the failure that started this tier.
            getLogger().severe("INDUCED: a severe line with every scenario green");
        }

        if ("hang".equals(INDUCE)) {
            getLogger().info("INDUCED: not writing the result file, the runner must time out");
            return;
        }

        writeResult(lines);
        Bukkit.shutdown();
    }

    private String run(Scenario scenario) {
        try {
            if ("assert".equals(INDUCE)) {
                Assert.that(false, "INDUCED: " + scenario.name() + " was told to assert something false");
            }
            scenario.body().run();
            getLogger().info("PASS " + scenario.name());
            return "PASS";
        } catch (Throwable t) {
            // Reported rather than thrown: one broken scenario must not stop the
            // rest from running, and the runner needs the result file to exist
            // in order to tell a failure from a run that never happened.
            getLogger().warning("FAIL " + scenario.name() + ": " + describe(t));
            return "FAIL " + describe(t);
        }
    }

    private String describe(Throwable t) {
        String message = t.getMessage();
        String detail = (message == null || message.isEmpty()) ? t.toString() : message;
        return detail.replace('\n', ' ').replace('\r', ' ');
    }

    private void writeResult(List<String> lines) {
        File marker = new File(System.getProperty("tradeshop.it.marker",
                new File(getDataFolder().getParentFile().getParentFile(), "it-result.txt").getPath()));

        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(marker.toPath(), StandardCharsets.UTF_8))) {
            for (String line : lines) {
                out.println(line);
            }
        } catch (IOException e) {
            // Nothing useful left to do: without the file the runner reports a
            // run that never happened, which is the right verdict anyway.
            getLogger().severe("could not write the result file at " + marker + ": " + e);
        }
    }

    /** A named piece of behaviour and the moves that check it. */
    private record Scenario(String name, Runnable body) {
    }
}
