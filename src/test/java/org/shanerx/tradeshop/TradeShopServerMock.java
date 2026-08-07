package org.shanerx.tradeshop;

import be.seeseemelk.mockbukkit.ServerMock;

import java.util.HashMap;
import java.util.Map;

/**
 * A {@link ServerMock} with the gaps TradeShop happens to fall into filled in.
 *
 * <p>MockBukkit throws {@code UnimplementedOperationException} for API it has not
 * covered, and JUnit reads that as a failed assumption — so the test is
 * <em>skipped</em> and the build still goes green. Every override here exists
 * because TradeShop hit that and the run reported success without testing
 * anything.
 */
public class TradeShopServerMock extends ServerMock {

    /** {@code onEnable} calls {@code aliasCheck("ts")}, which reads the alias map. */
    @Override
    public Map<String, String[]> getCommandAliases() {
        return new HashMap<>();
    }
}
