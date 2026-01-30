package vue.llm.util;

public class ProgressBar {

    private final int total;
    private final long startTime;
    private int current = 0;
    private static final int BAR_WIDTH = 40;

    public ProgressBar(int total) {
        this.total = total;
        this.startTime = System.currentTimeMillis();
    }

    public void step(String file, String route) {
        current++;
        render(file, route);
    }

    private void render(String file, String route) {
        double percent = (double) current / total;
        int filled = (int) (percent * BAR_WIDTH);

        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < BAR_WIDTH; i++) {
            bar.append(i < filled ? '█' : '░');
        }

        long now = System.currentTimeMillis();
        long elapsed = now - startTime;
        long eta = current == 0 ? 0 : (elapsed * (total - current) / current);

        String line = String.format(
                "\r[%s] %d/%d %.1f%% | elapsed %s | ETA %s | %s -> %s",
                bar,
                current,
                total,
                percent * 100,
                format(elapsed),
                format(eta),
                shortName(file),
                route
        );

        System.out.print(line);
        System.out.flush();

        if (current == total) {
            System.out.println();
        }
    }

    private String format(long ms) {
        long sec = ms / 1000;
        long min = sec / 60;
        sec %= 60;
        return String.format("%dm%02ds", min, sec);
    }

    private String shortName(String path) {
        if (path == null) return "";
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }
}
