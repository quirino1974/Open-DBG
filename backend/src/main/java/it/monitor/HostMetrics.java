package it.monitor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Risorse di un host fisico, lette da node_exporter (formato Prometheus, endpoint /metrics).
 * La CPU è una percentuale: serve un campione precedente, quindi il primo rilevamento non la calcola.
 */
public class HostMetrics {

    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    /** Ultimo campione di CPU per endpoint: {idle, totale} in secondi. */
    static final Map<String, double[]> CPU_PREV = new ConcurrentHashMap<>();

    static Map<String, Object> stats(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IllegalStateException("Metriche host: HTTP " + res.statusCode());

        double idle = 0, total = 0, cores = 0, memTotal = 0, memAvail = 0;
        java.util.Set<String> cpus = new java.util.HashSet<>();
        for (String line : res.body().split("\n")) {
            if (line.startsWith("node_cpu_seconds_total{")) {
                double v = Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
                total += v;
                if (line.contains("mode=\"idle\"")) idle += v;
                cpus.add(line.substring(line.indexOf("cpu=\""), line.indexOf('"', line.indexOf("cpu=\"") + 5) + 1));
            } else if (line.startsWith("node_memory_MemTotal_bytes ")) {
                memTotal = Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
            } else if (line.startsWith("node_memory_MemAvailable_bytes ")) {
                memAvail = Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
            }
        }
        cores = cpus.size();

        Double cpuPercent = null;
        double[] prev = CPU_PREV.put(url, new double[]{idle, total});
        if (prev != null && total > prev[1]) {
            double busy = (total - idle) - (prev[1] - prev[0]);
            double all = total - prev[1];
            cpuPercent = Math.round(Math.max(0, busy / all) * 1000.0) / 10.0;
        }

        long used = (long) Math.max(0, memTotal - memAvail);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cpuPercent", cpuPercent);
        out.put("cpuCore", (int) cores);
        out.put("memUsedBytes", used);
        out.put("memLimitBytes", (long) memTotal);
        out.put("memPercent", memTotal > 0 ? Math.round(used * 1000.0 / memTotal) / 10.0 : null);
        return out;
    }
}
