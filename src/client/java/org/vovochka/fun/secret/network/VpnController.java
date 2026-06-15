package org.vovochka.fun.secret.network;

import org.vovochka.fun.secret.Secret;

import java.io.IOException;
import java.net.URL;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

public class VpnController {

    public String getCurrentIp() {
        try {
            URL url = new URL("https://api.ipify.org");
            try (Scanner sc = new Scanner(url.openStream())) {
                return sc.hasNextLine() ? sc.nextLine().trim() : null;
            }
        } catch (IOException e) {
            Secret.LOGGER.error("getCurrentIp error: {}", e.getMessage());
            return null;
        }
    }

    private boolean runWarp(String... args) {
        try {
            String[] cmd = new String[args.length + 1];
            cmd[0] = "warp-cli";
            System.arraycopy(args, 0, cmd, 1, args.length);

            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();
            boolean ok = p.waitFor(15, TimeUnit.SECONDS);
            if (!ok) p.destroy();
            Thread.sleep(2000);
            return ok && p.exitValue() == 0;
        } catch (Exception e) {
            Secret.LOGGER.error("warp-cli error: {}", e.getMessage());
            return false;
        }
    }

    public boolean changeIp() {
        String oldIp = getCurrentIp();
        Secret.LOGGER.info("Old IP: {}", oldIp);

        for (int i = 1; i <= 8; i++) {
            Secret.LOGGER.info("VPN change attempt {}/8", i);

            runWarp("disconnect");
            runWarp("connect");

            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}

            String newIp = getCurrentIp();
            if (newIp != null && !newIp.equals(oldIp)) {
                Secret.LOGGER.info("✅ IP changed: {} → {}", oldIp, newIp);
                return true;
            }
            Secret.LOGGER.warn("IP unchanged ({}), retrying...", newIp);
        }

        Secret.LOGGER.error("❌ Failed to change IP");
        return false;
    }
}