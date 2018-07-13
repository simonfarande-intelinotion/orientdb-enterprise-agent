package com.orientechnologies.agent.profiler;

import com.orientechnologies.common.profiler.OrientDBProfiler;
import com.orientechnologies.common.profiler.metrics.*;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Created by Enrico Risa on 11/07/2018.
 */

public class OrientDBEnterpriseProfilerTest {

  @Test
  public void profilerCounterTest() {

    OrientDBProfiler profiler = new OrientDBEnterpriseProfiler();

    OCounter counter = profiler.counter("test", "Test description");

    for (int i = 0; i < 10; i++) {
      counter.inc();
    }

    Assert.assertEquals(10, counter.getCount());

    Assert.assertEquals(1, profiler.getMetrics().size());

    Assert.assertEquals(counter, profiler.counter("test", ""));

    for (int i = 0; i < 10; i++) {
      counter.dec();
    }

    Assert.assertEquals(0, counter.getCount());

    counter.inc(10);

    Assert.assertEquals(10, counter.getCount());

    counter.dec(10);

    Assert.assertEquals(0, counter.getCount());
  }

  @Test
  public void profilerMeterTest() {

    OrientDBProfiler profiler = new OrientDBEnterpriseProfiler();

    OMeter meter = profiler.meter("test", "Test description");

    for (int i = 0; i < 10; i++) {
      meter.mark();
    }

    Assert.assertEquals(10, meter.getCount());

    Assert.assertEquals(1, profiler.getMetrics().size());

    Assert.assertEquals(meter, profiler.meter("test", ""));

    meter.mark(10);

    Assert.assertEquals(20, meter.getCount());

  }

  @Test
  public void profilerGaugeTest() {

    OrientDBProfiler profiler = new OrientDBEnterpriseProfiler();

    List<String> values = new ArrayList<>();

    OGauge<Integer> meter = profiler.gauge("test", "Test description", () -> values.size());

    Assert.assertEquals((int) meter.getValue(), values.size());

    values.add("Test");

    Assert.assertEquals((int) meter.getValue(), values.size());
  }

  @Test
  public void profilerHistogramTest() {

    OrientDBProfiler profiler = new OrientDBEnterpriseProfiler();

    OHistogram histogram = profiler.histogram("test", "Test description");

    Assert.assertEquals(0, histogram.getCount());

    histogram.update(10);

    Assert.assertEquals(1, histogram.getCount());

    Assert.assertEquals(10, histogram.getSnapshot().getMax());
    Assert.assertEquals(10, histogram.getSnapshot().getMin());

    histogram.update(5);

    Assert.assertEquals(10, histogram.getSnapshot().getMax());
    Assert.assertEquals(5, histogram.getSnapshot().getMin());

    Assert.assertEquals(7.5, histogram.getSnapshot().getMean(), 0);
    Assert.assertEquals(10, histogram.getSnapshot().getMedian(), 0);
  }

  @Test
  public void profilerTimerTest() throws InterruptedException {

    OrientDBProfiler profiler = new OrientDBEnterpriseProfiler();

    OTimer timer = profiler.timer("test", "Test description");

    Assert.assertEquals(0, timer.getCount());

    OTimer.OContext ctx = timer.time();

    Assert.assertEquals(0, timer.getCount());

    Thread.sleep(1000);

    long finalTime = ctx.stop();

    Assert.assertEquals(1, timer.getCount());

    assertThat(TimeUnit.NANOSECONDS.toMillis(finalTime)).isGreaterThan(1000);

  }
}