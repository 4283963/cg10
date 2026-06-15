package com.cg10.paper.service;

import com.cg10.paper.config.InfluxDBConfig;
import com.influxdb.client.WriteApi;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class InfluxDBStorageService {

    private final WriteApi writeApi;
    private final InfluxDBConfig influxDBConfig;

    private final AtomicLong writeCount = new AtomicLong(0);
    private final AtomicBoolean lastWriteSuccess = new AtomicBoolean(true);
    private final AtomicLong lastWriteTime = new AtomicLong(0);

    public void store(int cylinderId, long timestampMs, double[] scanLine,
                      double[] zoneTemps, double avgTemp) {
        try {
            Instant time = Instant.ofEpochMilli(timestampMs);

            Point mainPoint = Point.measurement("cylinder_temperature")
                    .addTag("cylinder_id", String.valueOf(cylinderId))
                    .addField("avg_temp", avgTemp)
                    .addField("min_temp", minOf(scanLine))
                    .addField("max_temp", maxOf(scanLine))
                    .time(time, WritePrecision.MS);
            writeApi.writePoint(mainPoint);

            for (int i = 0; i < zoneTemps.length; i++) {
                Point zonePoint = Point.measurement("zone_temperature")
                        .addTag("cylinder_id", String.valueOf(cylinderId))
                        .addTag("zone_index", String.valueOf(i))
                        .addField("temperature", zoneTemps[i])
                        .time(time, WritePrecision.MS);
                writeApi.writePoint(zonePoint);
            }

            int step = Math.max(1, scanLine.length / 16);
            for (int i = 0; i < scanLine.length; i += step) {
                Point samplePoint = Point.measurement("scan_sample")
                        .addTag("cylinder_id", String.valueOf(cylinderId))
                        .addTag("point_index", String.valueOf(i))
                        .addField("temperature", scanLine[i])
                        .time(time, WritePrecision.MS);
                writeApi.writePoint(samplePoint);
            }

            writeCount.incrementAndGet();
            lastWriteSuccess.set(true);
            lastWriteTime.set(System.currentTimeMillis());

        } catch (Exception e) {
            lastWriteSuccess.set(false);
            if (writeCount.get() % 100 == 0) {
                log.warn("写入InfluxDB失败", e);
            }
        }
    }

    private double minOf(double[] arr) {
        double m = Double.MAX_VALUE;
        for (double v : arr) if (v < m) m = v;
        return m;
    }

    private double maxOf(double[] arr) {
        double m = Double.MIN_VALUE;
        for (double v : arr) if (v > m) m = v;
        return m;
    }

    public boolean isConnected() {
        return System.currentTimeMillis() - lastWriteTime.get() < 10000 || lastWriteSuccess.get();
    }

    public long getWriteCount() {
        return writeCount.get();
    }
}
